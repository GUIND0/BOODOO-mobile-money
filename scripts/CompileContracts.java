import java.nio.file.*;import java.util.*;import org.yaml.snakeyaml.*;import org.yaml.snakeyaml.constructor.SafeConstructor;
/** Assemble base and feature OpenAPI contracts without network access. */
public class CompileContracts {
 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
  Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath();Path output=root.resolve("deploy/openapi");Files.createDirectories(output);
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
   ((Map<String,Object>)document.get("info")).put("version","0.1.0");Files.writeString(output.resolve(module.getFileName()+".yaml"),new Yaml(dump).dump(document));
  }}
 }
}
