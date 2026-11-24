import java.nio.file.*;import java.util.*;import org.yaml.snakeyaml.*;import org.yaml.snakeyaml.constructor.SafeConstructor;
/** Assemble base and feature OpenAPI contracts without network access. */
public class CompileContracts {
 static Object rewrite(Object value,String prefix){
  if(value instanceof String text && text.startsWith("#/components/schemas/"))return "#/components/schemas/"+prefix+"_"+text.substring("#/components/schemas/".length());
  if(value instanceof Map<?,?> source){var result=new LinkedHashMap<String,Object>();for(var entry:source.entrySet()){String key=entry.getKey().toString();result.put(key,key.equals("security")?List.of(Map.of("gatewayBearer",List.of())):rewrite(entry.getValue(),prefix));}return result;}
  if(value instanceof List<?> list)return list.stream().map(item->rewrite(item,prefix)).toList();return value;
 }

 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
  Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath();Path output=root.resolve("deploy/openapi");Files.createDirectories(output);
  var documents=new LinkedHashMap<String,Map<String,Object>>();
  var options=new LoaderOptions();options.setAllowDuplicateKeys(false);var yaml=new Yaml(new SafeConstructor(options));var dump=new DumperOptions();dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);dump.setWidth(110);
  try(var modules=Files.list(root.resolve("services"))){for(Path module:modules.sorted().toList()){
   Path base=module.resolve("openapi.yaml");if(!Files.exists(base)||module.getFileName().toString().equals("api-gateway"))continue;
   Map<String,Object> document=yaml.load(Files.readString(base));var paths=(Map<String,Object>)document.get("paths");
   var components=(Map<String,Object>)document.computeIfAbsent("components",key->new LinkedHashMap<>());var security=(Map<String,Object>)components.computeIfAbsent("securitySchemes",key->new LinkedHashMap<>());security.put("serviceBearer",Map.of("type","http","scheme","bearer","bearerFormat","JWT"));
   Path fragments=module.resolve("contracts");if(Files.isDirectory(fragments))try(var files=Files.list(fragments)){for(Path path:files.filter(p->p.toString().endsWith(".json")).sorted().toList()){
    Map<String,Object> fragment=yaml.load(Files.readString(path));for(var entry:((Map<String,Object>)fragment.get("paths")).entrySet()){
     var existing=(Map<String,Object>)paths.computeIfAbsent(entry.getKey(),key->new LinkedHashMap<>());existing.putAll((Map<String,Object>)entry.getValue());
    }
   }}
   documents.put(module.getFileName().toString(),document);
   ((Map<String,Object>)document.get("info")).put("version","0.1.0");Files.writeString(output.resolve(module.getFileName()+".yaml"),new Yaml(dump).dump(document));
  }}
  var publicPaths=new LinkedHashMap<String,Object>();var publicSchemas=new LinkedHashMap<String,Object>();
  for(var entry:documents.entrySet()){
   String prefix=entry.getKey().replace("-service","").replace('-','_');var document=entry.getValue();
   for(var path:((Map<String,Object>)document.get("paths")).entrySet())if(path.getKey().startsWith("/v1/")){if(publicPaths.putIfAbsent(path.getKey(),rewrite(path.getValue(),prefix))!=null)throw new IllegalStateException("Duplicate public API route");}
   var components=(Map<String,Object>)document.get("components");if(components.get("schemas") instanceof Map<?,?> schemas)for(var schema:schemas.entrySet())publicSchemas.put(prefix+"_"+schema.getKey(),rewrite(schema.getValue(),prefix));
  }
  var gateway=new LinkedHashMap<String,Object>();gateway.put("openapi","3.1.0");gateway.put("info",Map.of("title","BOODOO public API pilot","version","0.1.0","description","Gateway and destination audiences required; each destination enforces its own signed actor/scopes. Internal financial controls stay private."));gateway.put("paths",publicPaths);gateway.put("components",Map.of("schemas",publicSchemas,"securitySchemes",Map.of("gatewayBearer",Map.of("type","http","scheme","bearer","bearerFormat","JWT"))));Files.writeString(output.resolve("api-gateway.yaml"),new Yaml(dump).dump(gateway));
 }
}
