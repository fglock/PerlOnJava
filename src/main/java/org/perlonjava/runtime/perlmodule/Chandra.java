package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless Java backend for Chandra's portable Element API. */
public class Chandra extends PerlModuleBase {
    public static final String XS_VERSION = "0.30";
    private static final AtomicInteger IDS = new AtomicInteger();
    public Chandra(){super("Chandra",false);}
    public static void initialize(){Chandra m=new Chandra();try{m.registerElement("new","construct");m.registerElement("add_child",null);m.registerElement("render",null);}catch(Exception e){throw new IllegalStateException(e);}}
    private void registerElement(String perl,String java) throws NoSuchMethodException,IllegalAccessException {java.lang.invoke.MethodHandle mh=RuntimeCode.lookup.findStatic(Chandra.class,java==null?perl:java,RuntimeCode.methodType);RuntimeCode c=new RuntimeCode(mh,null,null);c.isStatic=true;c.packageName="Chandra::Element";c.subName=perl;GlobalVariable.getGlobalCodeRef("Chandra::Element::"+perl).set(new RuntimeScalar(c));}
    public static RuntimeList construct(RuntimeArray a,int c){RuntimeHash h=new RuntimeHash();RuntimeHash in=a.size()>1&&a.get(1).type==RuntimeScalarType.HASHREFERENCE?a.get(1).hashDeref():new RuntimeHash();h.setFromList(in.getList());if(!h.exists("tag").getBoolean())h.put("tag",new RuntimeScalar("div"));String id=h.exists("id").getBoolean()?h.get("id").toString():"_e_"+IDS.incrementAndGet();h.put("id",new RuntimeScalar(id));RuntimeHash attrs=new RuntimeHash();for(RuntimeScalar key:in.keys().elements){String k=key.toString();if(!Set.of("tag","id","class","style","data","raw","children").contains(k))attrs.put(k,in.get(k));}h.put("attributes",attrs.createAnonymousReference());RuntimeArray children=new RuntimeArray();if(in.exists("children").getBoolean())for(RuntimeScalar child:in.get("children").arrayDeref().elements)RuntimeArray.push(children,element(child));h.put("children",children.createAnonymousReference());return ReferenceOperators.bless(h.createReferenceWithTrackedElements(),new RuntimeScalar("Chandra::Element")).getList();}
    private static RuntimeScalar element(RuntimeScalar child){if(child.type==RuntimeScalarType.HASHREFERENCE&&((RuntimeBase)child.value).blessId==0)return construct(new RuntimeArray(List.of(new RuntimeScalar("Chandra::Element"),child)),RuntimeContextType.SCALAR).scalar();return child;}
    public static RuntimeList add_child(RuntimeArray a,int c){RuntimeScalar child=element(a.get(1));RuntimeArray.push(a.get(0).hashDeref().get("children").arrayDeref(),child);return child.getList();}
    public static RuntimeList render(RuntimeArray a,int c){return new RuntimeScalar(renderHash(a.get(0).hashDeref())).getList();}
    private static String renderHash(RuntimeHash h){String tag=h.get("tag").toString();StringBuilder s=new StringBuilder("<").append(tag);for(String k:new String[]{"id","class"})if(h.exists(k).getBoolean())s.append(" ").append(k).append("=\"").append(esc(h.get(k).toString())).append("\"");if(h.exists("attributes").getBoolean()){RuntimeHash attrs=h.get("attributes").hashDeref();for(RuntimeScalar key:attrs.keys().elements){String k=key.toString();s.append(" ").append(k).append("=\"").append(esc(attrs.get(k).toString())).append("\"");}}s.append(">");if(h.exists("raw").getBoolean())s.append(h.get("raw"));else if(h.exists("data").getBoolean())s.append(esc(h.get("data").toString()));if(h.exists("children").getBoolean())for(RuntimeScalar ch:h.get("children").arrayDeref().elements)s.append(ch.type==RuntimeScalarType.HASHREFERENCE?renderHash(ch.hashDeref()):esc(ch.toString()));return s.append("</").append(tag).append(">").toString();}
    private static String esc(String x){return x.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
}
