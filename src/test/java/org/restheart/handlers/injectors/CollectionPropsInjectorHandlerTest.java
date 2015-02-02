/*
 * RESTHeart - the data REST API server
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
package org.restheart.handlers.injectors;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import org.restheart.handlers.RequestContext;

/**
 *
 * @author Maurizio Turatti <maurizio@softinstigate.com>
 */
public class CollectionPropsInjectorHandlerTest {

    public CollectionPropsInjectorHandlerTest() {
    }

    @BeforeClass
    public static void setUpClass() {
    }

    @AfterClass
    public static void tearDownClass() {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testCheckCollectionPut() {
        System.out.println("testCheckCollectionPut");

        RequestContext context = createContext("/db/collection", "PUT");

        assertEquals(context.getType(), RequestContext.TYPE.COLLECTION);
        assertEquals(context.getMethod(), RequestContext.METHOD.PUT);
        assertEquals(false, CollectionPropsInjectorHandler.checkCollection(context));
    }

    @Test
    public void testCheckCollectionFilesPost() {
        System.out.println("testCheckCollectionFilesPost");
        
        RequestContext context = createContext("/db/fs.files", "POST");

        assertEquals(context.getType(), RequestContext.TYPE.COLLECTION_FILES);
        assertEquals(context.getMethod(), RequestContext.METHOD.POST);
        assertEquals(false, CollectionPropsInjectorHandler.checkCollection(context));
    }

    @Test
    public void testCheckCollectionRoot() {
        System.out.println("testCheckCollectionRoot");

        RequestContext context = createContext("/", "PUT");

        assertEquals(context.getType(), RequestContext.TYPE.ROOT);
        assertEquals(context.getMethod(), RequestContext.METHOD.PUT);
        assertEquals(false, CollectionPropsInjectorHandler.checkCollection(context));
    }

    private RequestContext createContext(String requestPath, String httpMethod) {
        HttpServerExchange exchange = new HttpServerExchange();
        exchange.setRequestPath(requestPath);
        exchange.setRequestMethod(new HttpString(httpMethod));
        RequestContext context = new RequestContext(exchange, "/", "*");
        return context;
    }

}
