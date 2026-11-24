package com.paymentplatform.api_gateway.client;
import org.springframework.boot.context.properties.ConfigurationProperties;import java.net.URI;import java.util.Map;
@ConfigurationProperties("boodoo.dependencies") public record DependencyProperties(Map<String,URI> origins){
 public DependencyProperties{if(origins==null||origins.isEmpty())throw new IllegalArgumentException("Explicit dependency origins are required");origins=Map.copyOf(origins);for(var origin:origins.values())if(origin==null||!("http".equals(origin.getScheme())||"https".equals(origin.getScheme()))||origin.getHost()==null||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null||!(origin.getPath().isEmpty()||origin.getPath().equals("/")))throw new IllegalArgumentException("Dependency URLs must be HTTP(S) origins without credentials, path or query");}
}
