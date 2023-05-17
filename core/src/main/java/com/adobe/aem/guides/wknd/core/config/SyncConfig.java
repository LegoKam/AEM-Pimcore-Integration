package com.adobe.aem.guides.wknd.core.config;

/*
 * This a proof of concept that demonstrates AEM - Pimcore integration. Do not use this code in Production.
 * At a high level it demonstrates the pulling and pushing of data from PimCore over to AEM.
 * POC demonstrates:
 *  1. Read Published Product SKUs from PimCore and create SKU folders in AEM under /content/dam/breville.
 *  2. Once an Asset from a Product SKU folder is approved, AEM syncs the Dynamic Media URL of the asset into PimCore.
 *
 * The below values can be overridden using an OSGi config file.
 */

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;

public @interface SyncConfig {

        @AttributeDefinition(name = "CRON Expression", description = "CRON Expression", type = AttributeType.STRING)
        String cronExp() default "0 * * * * ?";

        @AttributeDefinition(name = "SKU Sync Path", description = "SKU Sync Path", type = AttributeType.STRING)
        String syncPath() default "/content/dam/breville";

        @AttributeDefinition(name = "Scheduler Name", description = "Scheduler Name", type = AttributeType.STRING)
        String schedulerName() default "breville_sky_sync";

        @AttributeDefinition(name = "Pimcore graphql", description = "Pimcore graphql", type = AttributeType.STRING)
        String pimcoreGraphql() default "http://XX.XX.XX.XX/pimcore-graphql-webservices/BrevilleProducts";

        @AttributeDefinition(name = "Graphql products", description = "Graphql products", type = AttributeType.STRING)
        String graphqlProducts() default "{\"query\":\"query{getBrevilleProductListing{edges{node{id,image,sku,name,description,image}}}}\"}";

        @AttributeDefinition(name = "API key", description = "API key", type = AttributeType.STRING)
        String apikey() default "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

        @AttributeDefinition(name = "Approved Asset query", description = "Approved Asset query", type = AttributeType.STRING)
        String approvedAssets() default "SELECT * FROM [dam:Asset] AS s WHERE ISDESCENDANTNODE([/content/dam/breville]) and [jcr:content/metadata/dam:status] = 'approved'";

}
