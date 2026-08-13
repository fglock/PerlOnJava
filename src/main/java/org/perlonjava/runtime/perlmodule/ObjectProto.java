package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Portable object backend for Object::Proto's public class-definition API. */
public class ObjectProto extends PerlModuleBase {
    public static final String XS_VERSION = "0.19";
    private record Property(String name, boolean required, boolean readonly, boolean lazy,
                            String builder, RuntimeScalar defaultValue) {}
    private static final Map<String,List<Property>> CLASSES = new ConcurrentHashMap<>();

    public ObjectProto() { super("Object::Proto", false); }
    public static void initialize() {
        ObjectProto m = new ObjectProto();
        try {
            for (String name : new String[]{"define","list_types","clone","register_type",
                    "import_accessors","import_accessor","role","requires","with",
                    "before","after","around"}) m.registerMethod(name, null);
        } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
    }
    public static RuntimeList list_types(RuntimeArray a,int c) {
        RuntimeArray r=new RuntimeArray();
        for(String s:new String[]{"Any","Defined","Str","Int","Num","Bool","ArrayRef","HashRef","CodeRef","Object"}) RuntimeArray.push(r,new RuntimeScalar(s));
        return r.createAnonymousReference().getList();
    }
    public static RuntimeList define(RuntimeArray a,int c) {
        String cls=a.get(0).toString(); List<Property> props=new ArrayList<>();
        for(int i=1;i<a.size();i++) props.add(parse(a.get(i).toString()));
        CLASSES.put(cls,props);
        install(cls,"new",(args,ctx)->construct(cls,args));
        for(Property p:props) install(cls,p.name,(args,ctx)->access(p,args));
        return new RuntimeList();
    }
    private static Property parse(String spec) {
        String[] x=spec.split(":"); String name=x[0],builder=null; boolean req=false,ro=false,lazy=false; RuntimeScalar def=null;
        for(int i=1;i<x.length;i++){String q=x[i]; req|=q.equals("required"); ro|=q.equals("readonly"); lazy|=q.equals("lazy");
            if(q.startsWith("builder(")) builder=q.substring(8,q.length()-1);
            if(q.startsWith("default(")){String v=q.substring(8,q.length()-1); def=v.equals("[]")?new RuntimeArray().createAnonymousReference():v.equals("{}")?new RuntimeHash().createAnonymousReference():v.equals("undef")?new RuntimeScalar():new RuntimeScalar(v.replaceAll("^['\"]|['\"]$",""));}}
        return new Property(name,req,ro,lazy,builder,def);
    }
    private static RuntimeList construct(String cls,RuntimeArray a) {
        RuntimeHash h=new RuntimeHash(); List<Property> ps=CLASSES.get(cls); int start=!a.isEmpty()&&a.get(0).toString().equals(cls)?1:0;
        for(Property p:ps) if(p.defaultValue!=null) h.put(p.name, Clone.clone(new RuntimeArray(List.of(p.defaultValue)),RuntimeContextType.SCALAR).scalar());
        if(start<a.size()&&a.get(start).type==RuntimeScalarType.HASHREFERENCE) h.setFromList(a.get(start).hashDeref().getList());
        else for(int i=start;i+1<a.size();i+=2) h.put(a.get(i).toString(),a.get(i+1));
        for(Property p:ps) if(p.required&&!h.exists(p.name).getBoolean()) throw new IllegalArgumentException("Attribute ("+p.name+") is required");
        RuntimeScalar obj=ReferenceOperators.bless(h.createReferenceWithTrackedElements(),new RuntimeScalar(cls));
        String buildName=cls+"::BUILD";
        if(GlobalVariable.existsGlobalCodeRef(buildName)) RuntimeCode.apply(GlobalVariable.getGlobalCodeRef(buildName),new RuntimeArray(List.of(obj)),RuntimeContextType.VOID);
        return obj.getList();
    }
    private static RuntimeList access(Property p,RuntimeArray a) {
        RuntimeHash h=a.get(0).hashDeref();
        if(a.size()>1){if(p.readonly&&h.exists(p.name).getBoolean()) throw new IllegalStateException("Cannot assign a value to a read-only accessor"); h.put(p.name,a.get(1));}
        if(!h.exists(p.name).getBoolean()&&p.lazy){String cls=NameNormalizer.getBlessStr(((RuntimeBase)a.get(0).value).blessId); String b=p.builder!=null&&!p.builder.isEmpty()?p.builder:"_build_"+p.name; RuntimeScalar code=GlobalVariable.getGlobalCodeRef(cls+"::"+b); RuntimeScalar v=RuntimeCode.apply(code,new RuntimeArray(List.of(a.get(0))),RuntimeContextType.SCALAR).scalar(); h.put(p.name,v);}
        return h.get(p.name).getList();
    }
    private static void install(String cls,String name,PerlSubroutine body){RuntimeCode c=new RuntimeCode(body,null);c.packageName=cls;c.subName=name;GlobalVariable.getGlobalCodeRef(cls+"::"+name).set(new RuntimeScalar(c));}
    public static RuntimeList clone(RuntimeArray a,int c){return Clone.clone(a,c);}
    public static RuntimeList register_type(RuntimeArray a,int c){return new RuntimeList();}
    public static RuntimeList import_accessors(RuntimeArray a,int c){return new RuntimeList();}
    public static RuntimeList import_accessor(RuntimeArray a,int c){return new RuntimeList();}
    public static RuntimeList role(RuntimeArray a,int c){return define(a,c);}
    public static RuntimeList requires(RuntimeArray a,int c){return new RuntimeList();}
    public static RuntimeList with(RuntimeArray a,int c){return new RuntimeList();}
    public static RuntimeList before(RuntimeArray a,int c){return new RuntimeList();}
    public static RuntimeList after(RuntimeArray a,int c){return new RuntimeList();}
    public static RuntimeList around(RuntimeArray a,int c){return new RuntimeList();}
}
