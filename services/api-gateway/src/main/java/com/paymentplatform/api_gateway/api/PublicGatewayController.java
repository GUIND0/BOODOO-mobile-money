package com.paymentplatform.api_gateway.api;
import com.paymentplatform.api_gateway.client.DependencyClient;import jakarta.servlet.http.HttpServletRequest;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;import tools.jackson.databind.ObjectMapper;import tools.jackson.databind.JsonNode;import java.io.IOException;import java.util.*;
@RestController public class PublicGatewayController {
 private final DependencyClient client;private final ObjectMapper json;public PublicGatewayController(DependencyClient client,ObjectMapper json){this.client=client;this.json=json;}
 @RequestMapping(path="/v1/**",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PATCH}) ResponseEntity<?> route(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException{
  String path=request.getRequestURI();if(!path.matches("/[A-Za-z0-9/_-]+")||path.contains("//"))throw invalid(HttpStatus.BAD_REQUEST,"INVALID_PATH");
  String target=target(path);String query=request.getQueryString();if(query!=null){if(query.length()>8192)throw invalid(HttpStatus.URI_TOO_LONG,"QUERY_TOO_LARGE");path+="?"+query;}
  byte[] bytes=request.getInputStream().readNBytes(65537);if(bytes.length>65536)throw invalid(HttpStatus.PAYLOAD_TOO_LARGE,"REQUEST_TOO_LARGE");
  JsonNode body=null;
  if(bytes.length>0){
   if(request.getMethod().equals("GET"))throw invalid(HttpStatus.BAD_REQUEST,"GET_BODY_NOT_ALLOWED");
   try{if(request.getContentType()==null||!MediaType.parseMediaType(request.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON)||request.getHeader("Content-Encoding")!=null)throw invalid(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON_REQUIRED");}catch(InvalidMediaTypeException error){throw invalid(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON_REQUIRED");}
   try{body=json.readTree(bytes);}catch(RuntimeException error){throw invalid(HttpStatus.BAD_REQUEST,"INVALID_JSON");}
  }
  var headers=new HashMap<String,String>();for(String name:List.of("If-Match","If-None-Match")){String value=request.getHeader(name);if(value!=null){if(value.length()>256||value.chars().anyMatch(Character::isISOControl))throw invalid(HttpStatus.BAD_REQUEST,"INVALID_HEADER");headers.put(name,value);}}
  String key=request.getHeader("Idempotency-Key");if(key!=null&&!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw invalid(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY");
  var reply=client.sendWithHeaders(target,request.getMethod(),path,body,key,jwt.getTokenValue(),headers);if(reply.status()>=300&&reply.status()<400)throw DependencyClient.unavailable();
  var response=ResponseEntity.status(reply.status()).cacheControl(CacheControl.noStore());
  for(String name:List.of("ETag","Location","Idempotency-Replayed","Retry-After","WWW-Authenticate","Content-Type")){
   var values=reply.headers().get(name.toLowerCase(Locale.ROOT));if(values==null)continue;for(String value:values){if(name.equals("Location")&&(!value.startsWith("/v1/")||value.startsWith("//")||value.contains("\\")))continue;response.header(name,value);}
  }
  return response.body(reply.body());
 }
 private String target(String path){
  if(path.matches("/v1/customers/[^/]+/pin(?:/verify)?"))return "identity";
  if(path.matches("/v1/customers/[^/]+/notifications(?:/[^/]+/acknowledgement)?"))return "notification";
  if(path.equals("/v1/customers")||path.startsWith("/v1/customers/"))return "customer";
  if(path.equals("/v1/wallets")||path.startsWith("/v1/wallets/"))return "wallet";
  if(path.equals("/v1/transfers")||path.startsWith("/v1/transfers/"))return "transfer";
  throw invalid(HttpStatus.NOT_FOUND,"ROUTE_NOT_FOUND");
 }
 private ApiException invalid(HttpStatus status,String code){return new ApiException(status,code,"The public API request cannot be forwarded.");}
}
