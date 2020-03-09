/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.handlers.exchange;

import io.undertow.security.idm.Account;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.PathTemplateMatch;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.json.JsonMode;
import org.bson.json.JsonParseException;
import org.restheart.mongodb.Bootstrapper;
import org.restheart.mongodb.db.CursorPool;
import org.restheart.mongodb.db.sessions.ClientSessionImpl;
import static org.restheart.handlers.exchange.AbstractExchange.LOGGER;
import static org.restheart.handlers.exchange.ExchangeKeys.*;
import org.restheart.handlers.exchange.ExchangeKeys.DOC_ID_TYPE;
import org.restheart.handlers.exchange.ExchangeKeys.ETAG_CHECK_POLICY;
import org.restheart.handlers.exchange.ExchangeKeys.HAL_MODE;
import org.restheart.handlers.exchange.ExchangeKeys.TYPE;
import org.restheart.mongodb.Configuration;
import org.restheart.mongodb.representation.Resource;
import org.restheart.mongodb.utils.URLUtils;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class BsonRequest extends Request<BsonValue> {
    private final String whereUri;
    private final String whatUri;

    private final String[] pathTokens;

    private BsonDocument dbProps;
    private BsonDocument collectionProps;

    private BsonValue content;

    private Path filePath;

    private int page = 1;
    private int pagesize = 100;
    private boolean count = false;
    private CursorPool.EAGER_CURSOR_ALLOCATION_POLICY cursorAllocationPolicy;
    private Deque<String> filter = null;
    private BsonDocument aggregationVars = null; // aggregation vars
    private Deque<String> keys = null;
    private Deque<String> sortBy = null;
    private Deque<String> hint = null;
    private DOC_ID_TYPE docIdType = DOC_ID_TYPE.STRING_OID;

    private Resource.REPRESENTATION_FORMAT representationFormat;

    private BsonValue documentId;

    private String mappedUri = null;
    private String unmappedUri = null;

    private final String etag;

    private boolean forceEtagCheck = false;

    private BsonDocument shardKey = null;

    private boolean noProps = false;

    private boolean inError = false;

    private Account authenticatedAccount = null;

    private ClientSessionImpl clientSession = null;

    /**
     * the HAL mode
     */
    private HAL_MODE halMode = HAL_MODE.FULL;

    private final long requestStartTime = System.currentTimeMillis();

    // path template match
    private final PathTemplateMatch pathTemplateMatch;

    private final JsonMode jsonMode;

    private static final AttachmentKey<BsonRequest> BSON_REQUEST_ATTACHMENT_KEY
            = AttachmentKey.create(BsonRequest.class);

    protected BsonRequest(HttpServerExchange exchange,
            String requestUri,
            String resourceUri) {
        super(exchange);
        LOGGER = LoggerFactory.getLogger(BsonResponse.class);

        this.whereUri = URLUtils.removeTrailingSlashes(requestUri == null ? null
                : requestUri.startsWith("/") ? requestUri
                : "/" + requestUri);

        this.whatUri = URLUtils.removeTrailingSlashes(
                resourceUri == null ? null
                        : resourceUri.startsWith("/")
                        || "*".equals(resourceUri) ? resourceUri
                        : "/" + resourceUri);

        this.mappedUri = exchange.getRequestPath();

        if (exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY) != null) {
            this.pathTemplateMatch = exchange
                    .getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
        } else {
            this.pathTemplateMatch = null;
        }

        this.unmappedUri = unmapUri(exchange.getRequestPath());

        // "/db/collection/document" --> { "", "mappedDbName", "collection", "document" }
        this.pathTokens = this.unmappedUri.split(SLASH);

        // etag
        HeaderValues etagHvs = exchange.getRequestHeaders() == null
                ? null : exchange.getRequestHeaders().get(Headers.IF_MATCH);

        this.etag = etagHvs == null || etagHvs.getFirst() == null
                ? null
                : etagHvs.getFirst();

        this.forceEtagCheck = exchange
                .getQueryParameters()
                .get(ETAG_CHECK_QPARAM_KEY) != null;

        this.noProps = exchange.getQueryParameters().get(NO_PROPS_KEY) != null;

        var _jsonMode = exchange.getQueryParameters().containsKey(JSON_MODE_QPARAM_KEY)
                ? exchange.getQueryParameters().get(JSON_MODE_QPARAM_KEY).getFirst().toUpperCase()
                : null;

        if (_jsonMode != null) {
            JsonMode mode;

            try {
                mode = JsonMode.valueOf(_jsonMode.toUpperCase());
            } catch (IllegalArgumentException iae) {
                mode = null;
            }

            this.jsonMode = mode;
        } else {
            this.jsonMode = null;
        }
    }

    /**
     *
     * @param exchange
     *
     * the exchange request path (mapped uri) is rewritten replacing the
     * resourceUri with the requestUri
     *
     * the special resourceUri value * means any resource: the requestUri is
     * mapped to the root resource /
     *
     * example 1
     *
     * resourceUri = /db/mycollection requestUri = /
     *
     * then the requestPath / is rewritten to /db/mycollection
     *
     * example 2
     *
     * resourceUri = * requestUri = /data
     *
     * then the requestPath /data is rewritten to /
     *
     * @param requestUri the request URI to map to the resource URI
     * @param resourceUri the resource URI identifying a resource in the DB
     * @return the BsonRequest
     */
    public static BsonRequest init(HttpServerExchange exchange,
            String requestUri,
            String resourceUri) {
        var request = new BsonRequest(exchange, requestUri, resourceUri);
        
        exchange.putAttachment(BSON_REQUEST_ATTACHMENT_KEY, request);
        
        return request;
    }
    
    public static BsonRequest wrap(HttpServerExchange exchange) {
        var cached = exchange.getAttachment(BSON_REQUEST_ATTACHMENT_KEY);
        
        if (cached == null) {
            throw new IllegalStateException("BsonRequest.wrap() invoked "
                    + "before BsonRequest.init()");
        } else {
            return cached;
        }
    }

    /**
     *
     * @param dbName
     * @return true if the dbName is a reserved resource
     */
    public static boolean isReservedResourceDb(String dbName) {
        return !dbName.equalsIgnoreCase(_METRICS)
                && !dbName.equalsIgnoreCase(_SIZE)
                && !dbName.equalsIgnoreCase(_SESSIONS)
                && (dbName.equals(ADMIN)
                || dbName.equals(CONFIG)
                || dbName.equals(LOCAL)
                || dbName.startsWith(SYSTEM)
                || dbName.startsWith(UNDERSCORE)
                || dbName.equals(RESOURCES_WILDCARD_KEY));
    }

    /**
     *
     * @param collectionName
     * @return true if the collectionName is a reserved resource
     */
    public static boolean isReservedResourceCollection(String collectionName) {
        return collectionName != null
                && !collectionName.equalsIgnoreCase(_SCHEMAS)
                && !collectionName.equalsIgnoreCase(_METRICS)
                && !collectionName.equalsIgnoreCase(_META)
                && !collectionName.equalsIgnoreCase(_SIZE)
                && (collectionName.startsWith(SYSTEM)
                || collectionName.startsWith(UNDERSCORE)
                || collectionName.endsWith(FS_CHUNKS_SUFFIX)
                || collectionName.equals(RESOURCES_WILDCARD_KEY));
    }

    /**
     *
     * @param type
     * @param documentIdRaw
     * @return true if the documentIdRaw is a reserved resource
     */
    public static boolean isReservedResourceDocument(
            TYPE type,
            String documentIdRaw) {
        if (documentIdRaw == null) {
            return false;
        }

        return (documentIdRaw.startsWith(UNDERSCORE)
                || (type != TYPE.AGGREGATION
                && _AGGREGATIONS.equalsIgnoreCase(documentIdRaw)))
                && (type == TYPE.TRANSACTION
                || !_TRANSACTIONS.equalsIgnoreCase(documentIdRaw))
                && (documentIdRaw.startsWith(UNDERSCORE)
                || (type != TYPE.CHANGE_STREAM
                && _STREAMS.equalsIgnoreCase(documentIdRaw)))
                && !documentIdRaw.equalsIgnoreCase(_METRICS)
                && !documentIdRaw.equalsIgnoreCase(_SIZE)
                && !documentIdRaw.equalsIgnoreCase(_INDEXES)
                && !documentIdRaw.equalsIgnoreCase(_META)
                && !documentIdRaw.equalsIgnoreCase(DB_META_DOCID)
                && !documentIdRaw.startsWith(COLL_META_DOCID_PREFIX)
                && !documentIdRaw.equalsIgnoreCase(MIN_KEY_ID)
                && !documentIdRaw.equalsIgnoreCase(MAX_KEY_ID)
                && !documentIdRaw.equalsIgnoreCase(NULL_KEY_ID)
                && !documentIdRaw.equalsIgnoreCase(TRUE_KEY_ID)
                && !documentIdRaw.equalsIgnoreCase(FALSE_KEY_ID)
                && !(type == TYPE.AGGREGATION)
                && !(type == TYPE.CHANGE_STREAM)
                && !(type == TYPE.TRANSACTION)
                || (documentIdRaw.equals(RESOURCES_WILDCARD_KEY)
                && !(type == TYPE.BULK_DOCUMENTS));
    }

    /**
     *
     * @return type
     */
    public TYPE getType() {
        return selectRequestType(pathTokens);
    }

    public BsonValue getContent() {
        return this.content;
    }

    public void setContent(BsonValue content) {
        this.content = content;
    }

    static TYPE selectRequestType(String[] pathTokens) {
        TYPE type;

        if (pathTokens.length > 0 && pathTokens[pathTokens.length - 1].equalsIgnoreCase(_SIZE)) {
            if (pathTokens.length == 2) {
                type = TYPE.ROOT_SIZE;
            } else if (pathTokens.length == 3) {
                type = TYPE.DB_SIZE;
            } else if (pathTokens.length == 4 && pathTokens[2].endsWith(FS_FILES_SUFFIX)) {
                type = TYPE.FILES_BUCKET_SIZE;
            } else if (pathTokens.length == 4 && pathTokens[2].endsWith(_SCHEMAS)) {
                type = TYPE.SCHEMA_STORE_SIZE;
            } else if (pathTokens.length == 4) {
                type = TYPE.COLLECTION_SIZE;
            } else {
                type = TYPE.INVALID;
            }
        } else if (pathTokens.length > 2 && pathTokens[pathTokens.length - 1].equalsIgnoreCase(_META)) {
            if (pathTokens.length == 3) {
                type = TYPE.DB_META;
            } else if (pathTokens.length == 4 && pathTokens[2].endsWith(FS_FILES_SUFFIX)) {
                type = TYPE.FILES_BUCKET_META;
            } else if (pathTokens.length == 4 && pathTokens[2].endsWith(_SCHEMAS)) {
                type = TYPE.SCHEMA_STORE_META;
            } else if (pathTokens.length == 4) {
                type = TYPE.COLLECTION_META;
            } else {
                type = TYPE.INVALID;
            }
        } else if (pathTokens.length < 2) {
            type = TYPE.ROOT;
        } else if (pathTokens.length == 2
                && pathTokens[pathTokens.length - 1]
                        .equalsIgnoreCase(_SESSIONS)) {
            type = TYPE.SESSIONS;
        } else if (pathTokens.length == 3
                && pathTokens[pathTokens.length - 2]
                        .equalsIgnoreCase(_SESSIONS)) {
            type = TYPE.SESSION;
        } else if (pathTokens.length == 4
                && pathTokens[pathTokens.length - 3]
                        .equalsIgnoreCase(_SESSIONS)
                && pathTokens[pathTokens.length - 1]
                        .equalsIgnoreCase(_TRANSACTIONS)) {
            type = TYPE.TRANSACTIONS;
        } else if (pathTokens.length == 5
                && pathTokens[pathTokens.length - 4]
                        .equalsIgnoreCase(_SESSIONS)
                && pathTokens[pathTokens.length - 2]
                        .equalsIgnoreCase(_TRANSACTIONS)) {
            type = TYPE.TRANSACTION;
        } else if (pathTokens.length < 3
                && pathTokens[1].equalsIgnoreCase(_METRICS)) {
            type = TYPE.METRICS;
        } else if (pathTokens.length < 3) {
            type = TYPE.DB;
        } else if (pathTokens.length >= 3
                && pathTokens[2].endsWith(FS_FILES_SUFFIX)) {
            if (pathTokens.length == 3) {
                type = TYPE.FILES_BUCKET;
            } else if (pathTokens.length == 4
                    && pathTokens[3].equalsIgnoreCase(_INDEXES)) {
                type = TYPE.COLLECTION_INDEXES;
            } else if (pathTokens.length == 4
                    && !pathTokens[3].equalsIgnoreCase(_INDEXES)
                    && !pathTokens[3].equals(RESOURCES_WILDCARD_KEY)) {
                type = TYPE.FILE;
            } else if (pathTokens.length > 4
                    && pathTokens[3].equalsIgnoreCase(_INDEXES)) {
                type = TYPE.INDEX;
            } else if (pathTokens.length > 4
                    && !pathTokens[3].equalsIgnoreCase(_INDEXES)
                    && !pathTokens[4].equalsIgnoreCase(BINARY_CONTENT)) {
                type = TYPE.FILE;
            } else if (pathTokens.length == 5
                    && pathTokens[4].equalsIgnoreCase(BINARY_CONTENT)) {
                // URL: <host>/db/bucket.filePath/xxx/binary
                type = TYPE.FILE_BINARY;
            } else {
                type = TYPE.DOCUMENT;
            }
        } else if (pathTokens.length >= 3
                && pathTokens[2].endsWith(_SCHEMAS)) {
            if (pathTokens.length == 3) {
                type = TYPE.SCHEMA_STORE;
            } else {
                type = TYPE.SCHEMA;
            }
        } else if (pathTokens.length >= 3
                && pathTokens[2].equalsIgnoreCase(_METRICS)) {
            type = TYPE.METRICS;
        } else if (pathTokens.length < 4) {
            type = TYPE.COLLECTION;
        } else if (pathTokens.length == 4
                && pathTokens[3].equalsIgnoreCase(_METRICS)) {
            type = TYPE.METRICS;
        } else if (pathTokens.length == 4
                && pathTokens[3].equalsIgnoreCase(_INDEXES)) {
            type = TYPE.COLLECTION_INDEXES;
        } else if (pathTokens.length == 4
                && pathTokens[3].equals(RESOURCES_WILDCARD_KEY)) {
            type = TYPE.BULK_DOCUMENTS;
        } else if (pathTokens.length > 4
                && pathTokens[3].equalsIgnoreCase(_INDEXES)) {
            type = TYPE.INDEX;
        } else if (pathTokens.length == 4
                && pathTokens[3].equalsIgnoreCase(_AGGREGATIONS)) {
            type = TYPE.INVALID;
        } else if (pathTokens.length > 4
                && pathTokens[3].equalsIgnoreCase(_AGGREGATIONS)) {
            type = TYPE.AGGREGATION;
        } else if (pathTokens.length == 4
                && pathTokens[3].equalsIgnoreCase(_STREAMS)) {
            type = TYPE.INVALID;
        } else if (pathTokens.length > 4
                && pathTokens[3].equalsIgnoreCase(_STREAMS)) {
            type = TYPE.CHANGE_STREAM;
        } else {
            type = TYPE.DOCUMENT;
        }

        return type;
    }

    /**
     * given a mapped uri (/some/mapping/coll) returns the canonical uri
     * (/db/coll) URLs are mapped to mongodb resources by using the mongo-mounts
     * configuration properties. note that the mapped uri can make use of path
     * templates (/some/{path}/template/*)
     *
     * @param mappedUri
     * @return
     */
    private String unmapUri(String mappedUri) {
        // don't unmpa URIs statring with /_sessions
        if (mappedUri.startsWith("/".concat(_SESSIONS))) {
            return mappedUri;
        }

        if (this.pathTemplateMatch == null) {
            return unmapPathUri(mappedUri);
        } else {
            return unmapPathTemplateUri(mappedUri);
        }
    }

    private String unmapPathUri(String mappedUri) {
        String ret = URLUtils.removeTrailingSlashes(mappedUri);

        if (whatUri.equals("*")) {
            if (!this.whereUri.equals(SLASH)) {
                ret = ret.replaceFirst("^" + this.whereUri, "");
            }
        } else if (!this.whereUri.equals(SLASH)) {
            ret = URLUtils.removeTrailingSlashes(
                    ret.replaceFirst("^" + this.whereUri, this.whatUri));
        } else {
            ret = URLUtils.removeTrailingSlashes(
                    URLUtils.removeTrailingSlashes(this.whatUri) + ret);
        }

        if (ret.isEmpty()) {
            ret = SLASH;
        }

        return ret;
    }

    private String unmapPathTemplateUri(String mappedUri) {
        String ret = URLUtils.removeTrailingSlashes(mappedUri);
        String rewriteUri = replaceParamsWithActualValues();

        String replacedWhatUri = replaceParamsWithinWhatUri();
        // replace params with in whatUri
        // eg what: /{account}, where: /{account/*

        // now replace mappedUri with resolved path template
        if (replacedWhatUri.equals("*")) {
            if (!this.whereUri.equals(SLASH)) {
                ret = ret.replaceFirst("^" + rewriteUri, "");
            }
        } else if (!this.whereUri.equals(SLASH)) {
            ret = URLUtils.removeTrailingSlashes(
                    ret.replaceFirst("^" + rewriteUri, replacedWhatUri));
        } else {
            ret = URLUtils.removeTrailingSlashes(
                    URLUtils.removeTrailingSlashes(replacedWhatUri) + ret);
        }

        if (ret.isEmpty()) {
            ret = SLASH;
        }

        return ret;
    }

    /**
     * given a canonical uri (/db/coll) returns the mapped uri
     * (/some/mapping/uri) relative to this context. URLs are mapped to mongodb
     * resources via the mongo-mounts configuration properties
     *
     * @param unmappedUri
     * @return
     */
    public String mapUri(String unmappedUri) {
        if (this.pathTemplateMatch == null) {
            return mapPathUri(unmappedUri);
        } else {
            return mapPathTemplateUri(unmappedUri);
        }
    }

    private String mapPathUri(String unmappedUri) {
        String ret = URLUtils.removeTrailingSlashes(unmappedUri);

        if (whatUri.equals("*")) {
            if (!this.whereUri.equals(SLASH)) {
                return this.whereUri + unmappedUri;
            }
        } else {
            ret = URLUtils.removeTrailingSlashes(
                    ret.replaceFirst("^" + this.whatUri, this.whereUri));
        }

        if (ret.isEmpty()) {
            ret = SLASH;
        } else {
            ret = ret.replaceAll("//", "/");
        }

        return ret;
    }

    private String mapPathTemplateUri(String unmappedUri) {
        String ret = URLUtils.removeTrailingSlashes(unmappedUri);
        String rewriteUri = replaceParamsWithActualValues();
        String replacedWhatUri = replaceParamsWithinWhatUri();

        // now replace mappedUri with resolved path template
        if (replacedWhatUri.equals("*")) {
            if (!this.whereUri.equals(SLASH)) {
                return rewriteUri + unmappedUri;
            }
        } else {
            ret = URLUtils.removeTrailingSlashes(
                    ret.replaceFirst("^" + replacedWhatUri, rewriteUri));
        }

        if (ret.isEmpty()) {
            ret = SLASH;
        }

        return ret;
    }

    private String replaceParamsWithinWhatUri() {
        String uri = this.whatUri;
        // replace params within whatUri
        // eg what: /{prefix}_db, where: /{prefix}/*
        for (String key : this.pathTemplateMatch
                .getParameters().keySet()) {
            uri = uri.replace(
                    "{".concat(key).concat("}"),
                    this.pathTemplateMatch
                            .getParameters().get(key));
        }
        return uri;
    }

    private String replaceParamsWithActualValues() {
        String rewriteUri;
        // path template with variables resolved to actual values
        rewriteUri = this.pathTemplateMatch.getMatchedTemplate();
        // remove trailing wildcard from template
        if (rewriteUri.endsWith("/*")) {
            rewriteUri = rewriteUri.substring(0, rewriteUri.length() - 2);
        }
        // collect params
        this.pathTemplateMatch
                .getParameters()
                .keySet()
                .stream()
                .filter(key -> !key.equals("*"))
                .collect(Collectors.toMap(
                        key -> key,
                        key -> this.pathTemplateMatch
                                .getParameters().get(key)));
        // replace params with actual values
        for (String key : this.pathTemplateMatch
                .getParameters().keySet()) {
            rewriteUri = rewriteUri.replace(
                    "{".concat(key).concat("}"),
                    this.pathTemplateMatch
                            .getParameters().get(key));
        }
        return rewriteUri;
    }

    /**
     * check if the parent of the requested resource is accessible in this
     * request context
     *
     * for instance if /db/mycollection is mapped to /coll then:
     *
     * the db is accessible from the collection the root is not accessible from
     * the collection (since / is actually mapped to the db)
     *
     * @return true if parent of the requested resource is accessible
     */
    public boolean isParentAccessible() {
        return getType() == TYPE.DB
                ? mappedUri.split(SLASH).length > 1
                : mappedUri.split(SLASH).length > 2;
    }

    /**
     *
     * @return DB Name
     */
    public String getDBName() {
        return getPathTokenAt(1);
    }

    /**
     *
     * @return collection name
     */
    public String getCollectionName() {
        return getPathTokenAt(2);
    }

    /**
     *
     * @return document id
     */
    public String getDocumentIdRaw() {
        return getPathTokenAt(3);
    }

    /**
     *
     * @return index id
     */
    public String getIndexId() {
        return getPathTokenAt(4);
    }

    /**
     *
     * @return the txn id or null if request type is not SESSIONS, TRANSACTIONS
     * or TRANSACTION
     */
    public String getSid() {
        return isTxn() || isTxns() || isSessions() ? getPathTokenAt(2) : null;
    }

    /**
     *
     * @return the txn id or null if request type is not TRANSACTION
     */
    public long getTxnId() {
        return isTxn() ? Long.parseLong(getPathTokenAt(4)) : null;
    }

    /**
     *
     * @return collection name
     */
    public String getAggregationOperation() {
        return getPathTokenAt(4);
    }

    /**
     * @return change stream operation name
     */
    public String getChangeStreamOperation() {
        return getPathTokenAt(4);
    }

    /**
     *
     * @return
     */
    public String getChangeStreamIdentifier() {
        return getPathTokenAt(5);
    }

    /**
     *
     * @return URI
     * @throws URISyntaxException
     */
    public URI getUri() throws URISyntaxException {
        return new URI(Arrays.asList(pathTokens)
                .stream()
                .reduce(SLASH, (t1, t2) -> t1 + SLASH + t2));
    }

    /**
     *
     * @return isReservedResource
     */
    public boolean isReservedResource() {
        if (getType() == TYPE.ROOT) {
            return false;
        }

        return isReservedResourceDb(getDBName())
                || isReservedResourceCollection(getCollectionName())
                || isReservedResourceDocument(getType(), getDocumentIdRaw());
    }

    /**
     * @return the whereUri
     */
    public String getUriPrefix() {
        return whereUri;
    }

    /**
     * @return the whatUri
     */
    public String getMappingUri() {
        return whatUri;
    }

    /**
     * @return the page
     */
    public int getPage() {
        return page;
    }

    /**
     * @param page the page to set
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * @return the pagesize
     */
    public int getPagesize() {
        return pagesize;
    }

    /**
     * @param pagesize the pagesize to set
     */
    public void setPagesize(int pagesize) {
        this.pagesize = pagesize;
    }

    /**
     * @return the representationFormat
     */
    public Resource.REPRESENTATION_FORMAT getRepresentationFormat() {
        return representationFormat;
    }

    /**
     * sets representationFormat
     *
     * @param representationFormat
     */
    public void setRepresentationFormat(
            Resource.REPRESENTATION_FORMAT representationFormat) {
        this.representationFormat = representationFormat;
    }

    /**
     * @return the count
     */
    public boolean isCount() {
        return count
                || getType() == TYPE.ROOT_SIZE
                || getType() == TYPE.COLLECTION_SIZE
                || getType() == TYPE.FILES_BUCKET_SIZE
                || getType() == TYPE.SCHEMA_STORE_SIZE;
    }

    /**
     * @param count the count to set
     */
    public void setCount(boolean count) {
        this.count = count;
    }

    /**
     * @return the filter
     */
    public Deque<String> getFilter() {
        return filter;
    }

    /**
     * @param filter the filter to set
     */
    public void setFilter(Deque<String> filter) {
        this.filter = filter;
    }

    /**
     * @return the hint
     */
    public Deque<String> getHint() {
        return hint;
    }

    /**
     * @param hint the hint to set
     */
    public void setHint(Deque<String> hint) {
        this.hint = hint;
    }

    /**
     *
     * @return the $and composed filter qparam values
     */
    public BsonDocument getFiltersDocument() throws JsonParseException {
        final BsonDocument filterQuery = new BsonDocument();

        if (filter != null) {
            if (filter.size() > 1) {
                BsonArray _filters = new BsonArray();

                filter.stream().forEach((String f) -> {
                    _filters.add(BsonDocument.parse(f));
                });

                filterQuery.put("$and", _filters);
            } else if (filter.size() == 1) {
                filterQuery.putAll(BsonDocument.parse(filter.getFirst()));  // this can throw JsonParseException for invalid filter parameters
            } else {
                return filterQuery;
            }
        }

        return filterQuery;
    }

    /**
     *
     * @return @throws JsonParseException
     */
    public BsonDocument getSortByDocument() throws JsonParseException {
        BsonDocument sort = new BsonDocument();

        if (sortBy == null) {
            sort.put("_id", new BsonInt32(-1));
        } else {
            sortBy.stream().forEach((s) -> {

                String _s = s.trim(); // the + sign is decoded into a space, in case remove it

                // manage the case where sort_by is a json object
                try {
                    BsonDocument _sort = BsonDocument.parse(_s);

                    sort.putAll(_sort);
                } catch (JsonParseException e) {
                    // sort_by is just a string, i.e. a property name
                    if (_s.startsWith("-")) {
                        sort.put(_s.substring(1), new BsonInt32(-1));
                    } else if (_s.startsWith("+")) {
                        sort.put(_s.substring(1), new BsonInt32(11));
                    } else {
                        sort.put(_s, new BsonInt32(1));
                    }
                }
            });
        }

        return sort;
    }

    /**
     *
     * @return @throws JsonParseException
     */
    public BsonDocument getHintDocument() throws JsonParseException {
        BsonDocument ret = new BsonDocument();

        if (hint == null || hint.isEmpty()) {
            return null;
        } else {
            hint.stream().forEach((s) -> {

                String _s = s.trim(); // the + sign is decoded into a space, in case remove it

                // manage the case where hint is a json object
                try {
                    BsonDocument _hint = BsonDocument.parse(_s);

                    ret.putAll(_hint);
                } catch (JsonParseException e) {
                    // ret is just a string, i.e. an index name
                    if (_s.startsWith("-")) {
                        ret.put(_s.substring(1), new BsonInt32(-1));
                    } else if (_s.startsWith("+")) {
                        ret.put(_s.substring(1), new BsonInt32(11));
                    } else {
                        ret.put(_s, new BsonInt32(1));
                    }
                }
            });
        }

        return ret;
    }

    /**
     *
     * @return @throws JsonParseException
     */
    public BsonDocument getProjectionDocument() throws JsonParseException {
        final BsonDocument projection = new BsonDocument();

        if (keys == null || keys.isEmpty()) {
            return null;
        } else {
            keys.stream().forEach((String f) -> {
                projection.putAll(BsonDocument.parse(f));  // this can throw JsonParseException for invalid keys parameters
            });
        }

        return projection;
    }

    /**
     * @return the aggregationVars
     */
    public BsonDocument getAggreationVars() {
        return aggregationVars;
    }

    /**
     * @param aggregationVars the aggregationVars to set
     */
    public void setAggregationVars(BsonDocument aggregationVars) {
        this.aggregationVars = aggregationVars;
    }

    /**
     * @return the sortBy
     */
    public Deque<String> getSortBy() {
        return sortBy;
    }

    /**
     * @param sortBy the sortBy to set
     */
    public void setSortBy(Deque<String> sortBy) {
        this.sortBy = sortBy;
    }

    /**
     * @return the collectionProps
     */
    public BsonDocument getCollectionProps() {
        return collectionProps;
    }

    /**
     * @param collectionProps the collectionProps to set
     */
    public void setCollectionProps(BsonDocument collectionProps) {
        this.collectionProps = collectionProps;
    }

    /**
     * @return the dbProps
     */
    public BsonDocument getDbProps() {
        return dbProps;
    }

    /**
     * @param dbProps the dbProps to set
     */
    public void setDbProps(BsonDocument dbProps) {
        this.dbProps = dbProps;
    }

    /**
     *
     * The unmapped uri is the cononical uri of a mongodb resource (e.g.
     * /db/coll).
     *
     * @return the unmappedUri
     */
    public String getUnmappedRequestUri() {
        return unmappedUri;
    }

    /**
     * The mapped uri is the exchange request uri. This is "mapped" by the
     * mongo-mounts mapping paramenters.
     *
     * @return the mappedUri
     */
    public String getMappedRequestUri() {
        return mappedUri;
    }

    /**
     * if mongo-mounts specifies a path template (i.e. /{foo}/*) this returns
     * the request template parameters (/x/y => foo=x, *=y)
     *
     * @return
     */
    public Map<String, String> getPathTemplateParamenters() {
        if (this.pathTemplateMatch == null) {
            return null;
        } else {
            return this.pathTemplateMatch.getParameters();
        }
    }

    /**
     *
     * @param index
     * @return pathTokens[index] if pathTokens.length > index, else null
     */
    private String getPathTokenAt(int index) {
        return pathTokens.length > index ? pathTokens[index] : null;
    }

    /**
     *
     * @return the cursorAllocationPolicy
     */
    public CursorPool.EAGER_CURSOR_ALLOCATION_POLICY getCursorAllocationPolicy() {
        return cursorAllocationPolicy;
    }

    /**
     * @param cursorAllocationPolicy the cursorAllocationPolicy to set
     */
    public void setCursorAllocationPolicy(
            CursorPool.EAGER_CURSOR_ALLOCATION_POLICY cursorAllocationPolicy) {
        this.cursorAllocationPolicy = cursorAllocationPolicy;
    }

    /**
     * @return the docIdType
     */
    public DOC_ID_TYPE getDocIdType() {
        return docIdType;
    }

    /**
     * @param docIdType the docIdType to set
     */
    public void setDocIdType(DOC_ID_TYPE docIdType) {
        this.docIdType = docIdType;
    }

    /**
     * @param documentId the documentId to set
     */
    public void setDocumentId(BsonValue documentId) {
        this.documentId = documentId;
    }

    /**
     * @return the documentId
     */
    public BsonValue getDocumentId() {
        if (isDbMeta()) {
            return new BsonString(DB_META_DOCID);
        } else if (isCollectionMeta()) {
            return new BsonString(COLL_META_DOCID_PREFIX.concat(getPathTokenAt(2)));
        } else {
            return documentId;
        }
    }

    /**
     * @return the clientSession
     */
    public ClientSessionImpl getClientSession() {
        return this.clientSession;
    }

    /**
     * @param clientSession the clientSession to set
     */
    public void setClientSession(ClientSessionImpl clientSession) {
        this.clientSession = clientSession;
    }

    /**
     * @return the jsonMode as specified by jsonMode query paramter
     */
    public JsonMode getJsonMode() {
        return jsonMode;
    }

    /**
     * @return the filePath
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * @param filePath the filePath to set
     */
    public void setFilePath(Path filePath) {
        this.filePath = filePath;
    }

    /**
     * @return keys
     */
    public Deque<String> getKeys() {
        return keys;
    }

    /**
     * @param keys keys to set
     */
    public void setKeys(Deque<String> keys) {
        this.keys = keys;
    }

    /**
     * @return the halMode
     */
    public HAL_MODE getHalMode() {
        return halMode;
    }

    /**
     *
     * @return
     */
    public boolean isFullHalMode() {
        return halMode == HAL_MODE.FULL || halMode == HAL_MODE.F;
    }

    /**
     * @param halMode the halMode to set
     */
    public void setHalMode(HAL_MODE halMode) {
        this.halMode = halMode;
    }

    /**
     * @see https://docs.mongodb.org/v3.2/reference/limits/#naming-restrictions
     * @return
     */
    public boolean isDbNameInvalid() {
        return isDbNameInvalid(getDBName());
    }

    /**
     *
     * @return
     */
    public long getRequestStartTime() {
        return requestStartTime;
    }

    /**
     * @param dbName
     * @see https://docs.mongodb.org/v3.2/reference/limits/#naming-restrictions
     * @return
     */
    public boolean isDbNameInvalid(String dbName) {
        return (dbName == null
                || dbName.contains(NUL)
                || dbName.contains(" ")
                || dbName.contains("/")
                || dbName.contains("\\")
                || dbName.contains(".")
                || dbName.contains("\"")
                || dbName.contains("$")
                || dbName.length() > 64
                || dbName.length() == 0);
    }

    /**
     * @see https://docs.mongodb.org/v3.2/reference/limits/#naming-restrictions
     * @return
     */
    public boolean isDbNameInvalidOnWindows() {
        return isDbNameInvalidOnWindows(getDBName());
    }

    /**
     * @param dbName
     * @see https://docs.mongodb.org/v3.2/reference/limits/#naming-restrictions
     * @return
     */
    public boolean isDbNameInvalidOnWindows(String dbName) {
        return (isDbNameInvalid()
                || dbName.contains("*")
                || dbName.contains("<")
                || dbName.contains(">")
                || dbName.contains(":")
                || dbName.contains(".")
                || dbName.contains("|")
                || dbName.contains("?"));
    }

    /**
     * @see https://docs.mongodb.org/v3.2/reference/limits/#naming-restrictions
     * @return
     */
    public boolean isCollectionNameInvalid() {
        return isCollectionNameInvalid(getCollectionName());
    }

    /**
     * @param collectionName
     * @see https://docs.mongodb.org/v3.2/reference/limits/#naming-restrictions
     * @return
     */
    public boolean isCollectionNameInvalid(String collectionName) {
        // collection starting with system. will return FORBIDDEN

        return (collectionName == null
                || collectionName.contains(NUL)
                || collectionName.contains("$")
                || collectionName.length() == 64);
    }

    /**
     *
     * @return
     */
    public String getETag() {
        return etag;
    }

    /**
     *
     * @return
     */
    public boolean isETagCheckRequired() {
        // if client specifies the If-Match header, than check it
        if (getETag() != null) {
            return true;
        }

        // if client requires the check via qparam return true
        if (forceEtagCheck) {
            return true;
        }

        // for documents consider db and coll etagDocPolicy metadata
        if (getType() == TYPE.DOCUMENT
                || getType() == TYPE.FILE) {
            // check the coll metadata
            BsonValue _policy = collectionProps != null
                    ? collectionProps.get(ETAG_DOC_POLICY_METADATA_KEY)
                    : null;

            LOGGER.trace(
                    "collection etag policy (from coll properties) {}",
                    _policy);

            if (_policy == null) {
                // check the db metadata
                _policy = dbProps != null ? dbProps.get(ETAG_DOC_POLICY_METADATA_KEY)
                        : null;
                LOGGER.trace(
                        "collection etag policy (from db properties) {}",
                        _policy);
            }

            ETAG_CHECK_POLICY policy = null;

            if (_policy != null && _policy.isString()) {
                try {
                    policy = ETAG_CHECK_POLICY
                            .valueOf(_policy.asString().getValue()
                                    .toUpperCase());
                } catch (IllegalArgumentException iae) {
                    policy = null;
                }
            }

            if (null != policy) {
                if (getMethod() == METHOD.DELETE) {
                    return policy != ETAG_CHECK_POLICY.OPTIONAL;
                } else {
                    return policy == ETAG_CHECK_POLICY.REQUIRED;
                }
            }
        }

        // for db consider db etagPolicy metadata
        if (getType() == TYPE.DB && dbProps != null) {
            // check the coll  metadata
            BsonValue _policy = dbProps.get(ETAG_POLICY_METADATA_KEY);

            LOGGER.trace("db etag policy (from db properties) {}", _policy);

            ETAG_CHECK_POLICY policy = null;

            if (_policy != null && _policy.isString()) {
                try {
                    policy = ETAG_CHECK_POLICY.valueOf(
                            _policy.asString().getValue()
                                    .toUpperCase());
                } catch (IllegalArgumentException iae) {
                    policy = null;
                }
            }

            if (null != policy) {
                if (getMethod() == METHOD.DELETE) {
                    return policy != ETAG_CHECK_POLICY.OPTIONAL;
                } else {
                    return policy == ETAG_CHECK_POLICY.REQUIRED;
                }
            }
        }

        // for collection consider coll and db etagPolicy metadata
        if (getType() == TYPE.COLLECTION && collectionProps != null) {
            // check the coll  metadata
            BsonValue _policy = collectionProps.get(ETAG_POLICY_METADATA_KEY);

            LOGGER.trace(
                    "coll etag policy (from coll properties) {}",
                    _policy);

            if (_policy == null) {
                // check the db metadata
                _policy = dbProps != null ? dbProps.get(ETAG_POLICY_METADATA_KEY)
                        : null;

                LOGGER.trace(
                        "coll etag policy (from db properties) {}",
                        _policy);
            }

            ETAG_CHECK_POLICY policy = null;

            if (_policy != null && _policy.isString()) {
                try {
                    policy = ETAG_CHECK_POLICY.valueOf(
                            _policy.asString().getValue()
                                    .toUpperCase());
                } catch (IllegalArgumentException iae) {
                    policy = null;
                }
            }

            if (null != policy) {
                if (getMethod() == METHOD.DELETE) {
                    return policy != ETAG_CHECK_POLICY.OPTIONAL;
                } else {
                    return policy == ETAG_CHECK_POLICY.REQUIRED;
                }
            }
        }

        // apply the default policy from configuration
        ETAG_CHECK_POLICY dbP = Configuration.get()
                .getDbEtagCheckPolicy();

        ETAG_CHECK_POLICY collP = Configuration.get()
                .getCollEtagCheckPolicy();

        ETAG_CHECK_POLICY docP = Configuration.get()
                .getDocEtagCheckPolicy();

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("default etag db check (from conf) {}", dbP);
            LOGGER.trace("default etag coll check (from conf) {}", collP);
            LOGGER.trace("default etag doc check (from conf) {}", docP);
        }

        ETAG_CHECK_POLICY policy = null;

        if (null != getType()) {
            switch (getType()) {
                case DB:
                    policy = dbP;
                    break;
                case COLLECTION:
                case FILES_BUCKET:
                case SCHEMA_STORE:
                    policy = collP;
                    break;
                default:
                    policy = docP;
            }
        }

        if (null != policy) {
            if (getMethod() == METHOD.DELETE) {
                return policy != ETAG_CHECK_POLICY.OPTIONAL;
            } else {
                return policy == ETAG_CHECK_POLICY.REQUIRED;
            }
        }

        return false;
    }

    /**
     * @return the shardKey
     */
    public BsonDocument getShardKey() {
        return shardKey;
    }

    /**
     * @param shardKey the shardKey to set
     */
    public void setShardKey(BsonDocument shardKey) {
        this.shardKey = shardKey;
    }

    /**
     * @return the noProps
     */
    public boolean isNoProps() {
        return noProps;
    }

    /**
     * @param noProps the noProps to set
     */
    public void setNoProps(boolean noProps) {
        this.noProps = noProps;
    }

    /**
     * @return the inError
     */
    public boolean isInError() {
        return inError;
    }

    /**
     * @param inError the inError to set
     */
    public void setInError(boolean inError) {
        this.inError = inError;
    }

    /**
     * @return the authenticatedAccount
     */
    @Override
    public Account getAuthenticatedAccount() {
        return authenticatedAccount;
    }

    /**
     * @param authenticatedAccount the authenticatedAccount to set
     */
    public void setAuthenticatedAccount(Account authenticatedAccount) {
        this.authenticatedAccount = authenticatedAccount;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.AGGREGATION
     */
    public boolean isAggregation() {
        return getType() == TYPE.AGGREGATION;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.BULK_DOCUMENTS
     */
    public boolean isBulkDocuments() {
        return getType() == TYPE.BULK_DOCUMENTS;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.COLLECTION
     */
    public boolean isCollection() {
        return getType() == TYPE.COLLECTION;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.COLLECTION_INDEXES
     */
    public boolean isCollectionIndexes() {
        return getType() == TYPE.COLLECTION_INDEXES;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.DB
     */
    public boolean isDb() {
        return getType() == TYPE.DB;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.DOCUMENT
     */
    public boolean isDocument() {
        return getType() == TYPE.DOCUMENT;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.FILE
     */
    public boolean isFile() {
        return getType() == TYPE.FILE;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.FILES_BUCKET
     */
    public boolean isFilesBucket() {
        return getType() == TYPE.FILES_BUCKET;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.FILE_BINARY
     */
    public boolean isFileBinary() {
        return getType() == TYPE.FILE_BINARY;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.INDEX
     */
    public boolean isIndex() {
        return getType() == TYPE.INDEX;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.ROOT
     */
    public boolean isRoot() {
        return getType() == TYPE.ROOT;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.TRANSACTIONS
     */
    public boolean isSessions() {
        return getType() == TYPE.SESSIONS;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.TRANSACTIONS
     */
    public boolean isTxns() {
        return getType() == TYPE.TRANSACTIONS;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.TRANSACTION
     */
    public boolean isTxn() {
        return getType() == TYPE.TRANSACTION;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.SCHEMA
     */
    public boolean isSchema() {
        return getType() == TYPE.SCHEMA;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.SCHEMA_STORE
     */
    public boolean isSchemaStore() {
        return getType() == TYPE.SCHEMA_STORE;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.ROOT_SIZE
     */
    public boolean isRootSize() {
        return getType() == TYPE.ROOT_SIZE;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.DB_SIZE
     */
    public boolean isDbSize() {
        return getType() == TYPE.DB_SIZE;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.DB_META
     */
    public boolean isDbMeta() {
        return getType() == TYPE.DB_META;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.COLLECTION_SIZE
     */
    public boolean isCollectionSize() {
        return getType() == TYPE.COLLECTION_SIZE;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.COLLECTION_META
     */
    public boolean isCollectionMeta() {
        return getType() == TYPE.COLLECTION_META;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.FILES_BUCKET_SIZE
     */
    public boolean isFilesBucketSize() {
        return getType() == TYPE.FILES_BUCKET_SIZE;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.FILES_BUCKET_META
     */
    public boolean isFilesBucketMeta() {
        return getType() == TYPE.FILES_BUCKET_META;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.SCHEMA_STORE_SIZE
     */
    public boolean isSchemaStoreSize() {
        return getType() == TYPE.SCHEMA_STORE_SIZE;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.SCHEMA_STORE_SIZE
     */
    public boolean isSchemaStoreMeta() {
        return getType() == TYPE.SCHEMA_STORE_META;
    }

    /**
     * helper method to check request resource type
     *
     * @return true if type is TYPE.METRICS
     */
    public boolean isMetrics() {
        return getType() == TYPE.METRICS;
    }
}
