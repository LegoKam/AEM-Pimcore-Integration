package com.adobe.aem.guides.wknd.core.scheduler;
/*
 * This a proof of concept that demonstrates AEM - Pimcore integration. Do not use this code in Production.
 * At a high level it demonstrates the pulling and pushing of data from PimCore over to AEM.
 * POC demonstrates:
 *  1. Read Published Product SKUs from PimCore and create SKU folders in AEM under /content/dam/breville.
 *  2. Once an Asset from a Product SKU folder is approved, AEM syncs the Dynamic Media URL of the asset into PimCore.
 */


import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.scheduler.Job;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.apache.sling.commons.scheduler.JobContext;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.aem.guides.wknd.core.config.SyncConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

@Component(service = Job.class, immediate = true)
public class PimcoreSync implements Job {

    public static final String DAM_SCENE_7_DOMAIN = "dam:scene7Domain";
    public static final String DAM_SCENE_7_TYPE = "dam:scene7Type";
    public static final String DAM_SCENE_7_FILE = "dam:scene7File";
    public static final String QUERY_MUTATION_UPDATE_BREVILLE_PRODUCT_ID_PREFIX = "{\"query\":\"mutation{updateBrevilleProduct(id:";
    public static final String QUERY_MUTATION_UPDATE_BREVILLE_IMAGE_PREFIX = ",input:{image:\\\"";
    public static final String QUERY_MUTATION_UPDATE_BREVILLE_SUFFIX = "\\\"}){success}}\"}";
    public static final String DAM_PATH_BREVILLE_PIM = "/content/dam/breville/pimcore";
    public static final String SKU = "sku";
    public static final String JCR_CONTENT = "jcr:content";
    public static final String ID = "id";
    @Reference
    ResourceResolverFactory resourceResolverFactory;

    @Reference
    Scheduler scheduler;

    private static final Logger logger = LoggerFactory.getLogger(PimcoreSync.class);
    private static String pimcoreInstance = null;
    private static String graphqlQuery = null;
    private static String apiKey = null;
    private static String approvedAssets = null;

    @Activate
    private void activate(SyncConfig configuration) {

        logger.info("*******************Activate method");
        ScheduleOptions scheduleOptions = scheduler.EXPR(configuration.cronExp());
        logger.info("*******************cronExp ::" + configuration.cronExp());

        Map<String, Serializable> enMap = new HashMap<String, Serializable>();
        enMap.put("assetPath", configuration.syncPath());
        logger.info("*******************syncPath ::" + configuration.syncPath());

        scheduleOptions.config(enMap);
        scheduleOptions.canRunConcurrently(false);
        scheduler.schedule(this, scheduleOptions);

        pimcoreInstance = configuration.pimcoreGraphql();
        graphqlQuery = configuration.graphqlProducts();
        apiKey = configuration.apikey();
        approvedAssets = configuration.approvedAssets();

        logger.info("*******************Activated::::" + scheduleOptions);
    }

    @Deactivate
    protected void deactivated(SyncConfig configuration) {
        logger.info("Deactivated!!");
    }

    @Override
    public void execute(JobContext jobContext) {
        logger.info("Job in progress----!!" + new Date());
        ResourceResolver resourceResolver = null;
        try {
            resourceResolver = getResourceResolver();
            String productSync = httpCall(graphqlQuery);
            if (productSync != null) {
                ArrayList<Map<String, String>> skuArray = getSKUAndID(productSync);
                for (Map skuIdMap : skuArray) {
                    logger.debug("SKUU>>>>>>>" + skuIdMap);
                    Node folderNode = createFolder((String) skuIdMap.get(SKU), resourceResolver);
                    if (folderNode != null) {
                        Node jcrContent = folderNode.getNode(JCR_CONTENT);
                        jcrContent.setProperty("productId", skuIdMap.get(ID).toString());
                        jcrContent.getSession().save();
                    }
                }
            }

            //Get all approved assets
            String[] approvedAssets = getApprovedAssets(resourceResolver);


        } catch (Exception exception) {
            logger.error("execute----!!", exception);
        } finally {
            if (resourceResolver != null) {
                resourceResolver.close();
            }
        }
    }

    private String[] getApprovedAssets(ResourceResolver resourceResolver) {
        try {
            Session session = resourceResolver.adaptTo(Session.class);
            QueryManager queryManager = session.getWorkspace().getQueryManager();

            Query query = queryManager.createQuery(approvedAssets, Query.JCR_SQL2);

            QueryResult queryResult = query.execute();
            NodeIterator nodes = queryResult.getNodes();

            while (nodes.hasNext()) {
                Node assetNode = nodes.nextNode();
                logger.debug("Approved Assets???>>>" + assetNode);
                String METADATA_NODE = "jcr:content/metadata";
                if (assetNode.hasNode(METADATA_NODE)) {
                    Node metadataNode = assetNode.getNode(METADATA_NODE);
                    if (metadataNode.hasProperty(DAM_SCENE_7_DOMAIN) &&
                            metadataNode.hasProperty(DAM_SCENE_7_TYPE) &&
                            metadataNode.hasProperty(DAM_SCENE_7_FILE)) {

                        String scene7Domain = metadataNode.getProperty(DAM_SCENE_7_DOMAIN).getString();
                        String scene7Type = metadataNode.getProperty(DAM_SCENE_7_TYPE).getString();
                        String scene7File = metadataNode.getProperty(DAM_SCENE_7_FILE).getString();

                        String scene7URL = (scene7Domain + "is/" + scene7Type.toLowerCase() + "/" + scene7File);

                        logger.debug("DM URL::>>>>" + (scene7Domain + "is/" + scene7Type.toLowerCase() + "/" + scene7File));

                        logger.debug("Prop===>" + metadataNode.getProperty(DAM_SCENE_7_DOMAIN));
                        logger.debug("Prop===>" + metadataNode.getProperty(DAM_SCENE_7_TYPE));
                        logger.debug("Prop===>" + metadataNode.getProperty(DAM_SCENE_7_FILE));

                        String id = getID(resourceResolver, assetNode);
                        updateImagePath(id, scene7URL);
                    }
                }


            }
        } catch (RepositoryException e) {
            logger.error("Error", e);
        }
        return null;
    }

    private void updateImagePath(String id, String scene7URL) {
        String mutationURL = QUERY_MUTATION_UPDATE_BREVILLE_PRODUCT_ID_PREFIX + id +
                QUERY_MUTATION_UPDATE_BREVILLE_IMAGE_PREFIX + scene7URL + QUERY_MUTATION_UPDATE_BREVILLE_SUFFIX;
        logger.debug("Mutation URL::" + httpCall(mutationURL));
    }

    private String getID(ResourceResolver resourceResolver, Node assetNode) {
        try {
            Node jcrContentNode = assetNode.getParent().getNode(JCR_CONTENT);
            if (jcrContentNode.hasProperty("productId")) {
                return jcrContentNode.getProperty("productId").getString();
            }
        } catch (Exception e) {
            logger.error("Error", e);
        }
        return null;
    }


    private ResourceResolver getResourceResolver() throws LoginException {
        Map<String, Object> param = new HashMap<>();
        param.put(ResourceResolverFactory.SUBSERVICE, "content-svc-admin");
        return resourceResolverFactory.getServiceResourceResolver(param);
    }

    private Node createFolder(String sku, ResourceResolver resourceResolver) {
        try {
            Session session = resourceResolver.adaptTo(Session.class);
            Node brevilleFolder = resourceResolver.getResource(DAM_PATH_BREVILLE_PIM).adaptTo(Node.class);
            if (!Objects.requireNonNull(brevilleFolder).hasNode(sku)) {
                Node skuFolder = brevilleFolder.addNode(sku, "sling:Folder");
                skuFolder.setProperty("jcr:title", sku);
                skuFolder.addNode(JCR_CONTENT, "nt:unstructured");
                session.save();
                return skuFolder;
            }
        } catch (Exception exception) {
            logger.error("Error", exception);
        }
        return null;
    }

    private static ArrayList<Map<String, String>> getSKUAndID(String json) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode brevilleProductListing = rootNode.at("/data/getBrevilleProductListing/edges");
            ArrayList<Map<String, String>> skuArrayList = new ArrayList<>();
            for (int i = 0; i < brevilleProductListing.size(); i++) {
                Map<String, String> productKey = new HashMap<>();
                productKey.put(SKU, brevilleProductListing.at("/" + i + "/node/sku").asText());
                productKey.put(ID, brevilleProductListing.at("/" + i + "/node/id").asText());
                skuArrayList.add(productKey);
            }
            return skuArrayList;
        } catch (IOException ioe) {
            logger.error("error", ioe);
        }
        return null;
    }

    private static String httpCall(String graphQlString) {
        try {
            URL url = new URL(pimcoreInstance);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("accept", "application/json");
            con.setRequestProperty("X-API-Key", apiKey);
            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                byte[] input = graphQlString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.close();
            }
            con.connect();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine = null;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                logger.info("*************************** Response link====" + response.toString());
                logger.info(response.toString());
                return response.toString();
            }

        } catch (IOException ioe) {
            logger.error("*******************IOException::::", ioe);
        }
        return null;
    }

}
