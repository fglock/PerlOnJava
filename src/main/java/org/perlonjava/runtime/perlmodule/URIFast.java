package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;

/** Java implementation of the URI::Fast XS boundary. */
public final class URIFast extends PerlModuleBase {
    public static final String XS_VERSION = "0.55";
    private static final String[] FIELDS = {"scheme", "usr", "pwd", "host", "port", "path", "query", "frag"};

    public URIFast() { super("URI::Fast", false); }

    public static void initialize() {
        URIFast module = new URIFast();
        module.initializeExporter();
        module.defineExport("EXPORT_OK", "uri_split", "uri", "iri", "abs_uri", "html_url",
                "encode", "uri_encode", "url_encode", "decode", "uri_decode", "url_decode");
        String[] methods = {"encode", "uri_encode", "url_encode", "decode", "uri_decode", "url_decode",
                "new", "new_abs", "new_html_url", "uri", "iri", "abs_uri", "html_url", "uri_split",
                "to_string", "as_string", "TO_JSON", "normalize", "canonical", "absolute", "abs",
                "clear_scheme", "clear_path", "clear_query", "clear_frag", "clear_usr", "clear_pwd",
                "clear_host", "clear_port", "clear_auth", "raw_scheme", "raw_auth", "raw_path", "raw_query",
                "raw_frag", "raw_usr", "raw_pwd", "raw_host", "raw_port", "get_path", "get_query", "get_auth",
                "split_path", "split_path_compat", "get_query_keys", "get_query_hash", "get_param", "set_auth",
                "set_path", "set_path_array", "set_query", "set_param", "query_keyset", "scheme", "usr", "pwd",
                "host", "port", "frag", "fragment", "DESTROY", "explain", "debug"};
        try {
            for (String method : methods) {
                String javaMethod = switch (method) {
                    case "new" -> "newUri";
                    case "as_string", "TO_JSON" -> "to_string";
                    case "canonical" -> "normalize";
                    case "abs" -> "absolute";
                    case "fragment" -> "frag";
                    case "uri_encode", "url_encode" -> "encode";
                    case "uri_decode", "url_decode" -> "decode";
                    default -> method;
                };
                module.registerMethod(method, javaMethod, null);
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing URI::Fast method", e);
        }
    }

    private static RuntimeHash object(RuntimeScalar scalar) {
        if (scalar == null || !(scalar.value instanceof RuntimeHash)) throw new IllegalArgumentException("URI::Fast object expected");
        return (RuntimeHash) scalar.value;
    }
    private static String value(RuntimeHash hash, String key) {
        RuntimeScalar v = hash.get(key);
        return v == null || !v.getDefinedBoolean() ? "" : v.toString();
    }
    private static RuntimeScalar text(String value) { return new RuntimeScalar(value); }
    private static String arg(RuntimeArray args, int i) { return i < args.size() && args.get(i).getDefinedBoolean() ? args.get(i).toString() : ""; }
    private static RuntimeScalar make(String className, String source) {
        source = source == null ? "" : source.strip();
        RuntimeHash state = new RuntimeHash();
        for (String field : FIELDS) state.put(field, text(""));
        state.put("is_iri", new RuntimeScalar(className.endsWith("::IRI")));
        state.put("authority_present", new RuntimeScalar(false));
        try {
            String input = escapeIri(source);
            boolean bareAuthority = !input.startsWith("//")
                    && (input.matches("^[^/?#]+@[^/?#]+.*")
                    || input.matches("^[^/?#]+:[0-9]+(?:/.*)?$"));
            URI uri = new URI(bareAuthority ? "//" + input : input);
            put(state, "scheme", uri.getScheme());
            put(state, "path", uri.getRawPath());
            put(state, "query", uri.getRawQuery());
            put(state, "frag", uri.getRawFragment());
            String user = uri.getRawUserInfo();
            if (user != null) {
                int colon = user.indexOf(':');
                put(state, "usr", colon < 0 ? user : user.substring(0, colon));
                if (colon >= 0) put(state, "pwd", user.substring(colon + 1));
            }
            put(state, "host", uri.getHost());
            if (uri.getRawAuthority() != null || source.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*") || source.startsWith("//")) state.put("authority_present", new RuntimeScalar(true));
            if (uri.getPort() >= 0) put(state, "port", Integer.toString(uri.getPort()));
            if (uri.getRawAuthority() != null && uri.getHost() == null) parseAuthority(state, uri.getRawAuthority(), true);
        } catch (URISyntaxException ignored) {
            // URI::Fast deliberately accepts a wider set than java.net.URI.
            int hash = source.indexOf('#');
            if (hash >= 0) { put(state, "frag", source.substring(hash + 1)); source = source.substring(0, hash); }
            int query = source.indexOf('?');
            if (query >= 0) { put(state, "query", source.substring(query + 1)); source = source.substring(0, query); }
            int colon = source.indexOf(':');
            int slash = source.indexOf('/');
            if (colon > 0 && (slash < 0 || colon < slash)) { put(state, "scheme", source.substring(0, colon)); source = source.substring(colon + 1); }
            put(state, "path", source);
        }
        RuntimeScalar ref = state.createReference();
        ReferenceOperators.bless(ref, text(className));
        return ref;
    }
    private static String escapeIri(String source) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < source.length();) {
            int cp = source.codePointAt(i); i += Character.charCount(cp);
            if (cp <= 0x7e) out.append((char) cp);
            else for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) out.append(String.format("%%%02X", b & 255));
        }
        return out.toString();
    }
    private static void put(RuntimeHash hash, String key, String value) { if (value != null) hash.put(key, text(value)); }
    private static void parseAuthority(RuntimeHash hash, String authority, boolean raw) {
        String hostPort = authority;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String user = authority.substring(0, at); hostPort = authority.substring(at + 1);
            int colon = user.indexOf(':'); put(hash, "usr", stored(raw, colon < 0 ? user : user.substring(0, colon), "usr"));
            if (colon >= 0) put(hash, "pwd", stored(raw, user.substring(colon + 1), "pwd"));
        }
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.indexOf(']') < colon) { put(hash, "host", stored(raw, hostPort.substring(0, colon), "host")); put(hash, "port", hostPort.substring(colon + 1)); }
        else put(hash, "host", stored(raw, hostPort, "host"));
    }
    private static String stored(boolean raw, String value, String field) { return raw ? value : encode(value, allowed(field)); }
    private static String decode(String input) {
        byte[] bytes = new byte[input.length() * 4]; int n = 0;
        for (int i = 0; i < input.length();) {
            char c = input.charAt(i++);
            if (c == '%' && i + 1 < input.length()) {
                int hi = Character.digit(input.charAt(i), 16), lo = Character.digit(input.charAt(i + 1), 16);
                if (hi >= 0 && lo >= 0) { bytes[n++] = (byte) ((hi << 4) | lo); i += 2; continue; }
            }
            byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            for (byte b : raw) bytes[n++] = b;
        }
        return new String(bytes, 0, n, StandardCharsets.UTF_8);
    }
    private static boolean validUtf8Escapes(String input) {
        byte[] bytes = new byte[input.length() * 4]; int n = 0;
        for (int i=0;i<input.length();) {
            char c=input.charAt(i++);
            if(c=='%'&&i+1<input.length()) { int h=Character.digit(input.charAt(i),16),l=Character.digit(input.charAt(i+1),16); if(h>=0&&l>=0){bytes[n++]=(byte)((h<<4)|l);i+=2;continue;} }
            for(byte b:String.valueOf(c).getBytes(StandardCharsets.UTF_8))bytes[n++]=b;
        }
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes,0,n)); return true; }
        catch (java.nio.charset.CharacterCodingException e) { return false; }
    }
    private static String normalizeEscapes(String input) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<input.length();) {
            if(input.charAt(i)=='%'&&i+2<input.length()) {
                int h=Character.digit(input.charAt(i+1),16),l=Character.digit(input.charAt(i+2),16);
                if(h>=0&&l>=0) { int b=(h<<4)|l; if((b>='a'&&b<='z')||(b>='A'&&b<='Z')||(b>='0'&&b<='9')||"-._~".indexOf(b)>=0)out.append((char)b); else out.append(String.format("%%%02X",b)); i+=3; continue; }
            }
            out.append(input.charAt(i++));
        }
        return out.toString();
    }
    /** URI::Fast compound getters decode escaped UTF-8 but leave ASCII escapes intact. */
    private static String decodeCompound(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length();) {
            if (input.charAt(i) != '%') { out.append(input.charAt(i++)); continue; }
            int start = i;
            List<Byte> run = new ArrayList<>();
            while (i + 2 < input.length() && input.charAt(i) == '%') {
                int hi = Character.digit(input.charAt(i + 1), 16), lo = Character.digit(input.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) break;
                run.add((byte) ((hi << 4) | lo)); i += 3;
            }
            if (run.isEmpty()) { out.append(input.charAt(i++)); continue; }
            boolean utf8 = run.stream().anyMatch(b -> (b & 0x80) != 0);
            if (!utf8) out.append(input, start, i);
            else {
                byte[] bytes = new byte[run.size()]; for (int j=0;j<run.size();j++) bytes[j]=run.get(j);
                out.append(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return out.toString();
    }
    private static String encode(String input, String allowed) {
        StringBuilder out = new StringBuilder();
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0 || allowed.indexOf(c) >= 0) out.append((char)c);
            else out.append(String.format("%%%02X", c));
        }
        return out.toString();
    }
    private static String stringify(RuntimeScalar scalar) {
        RuntimeHash h = object(scalar);
        StringBuilder s = new StringBuilder();
        boolean iri = "1".equals(value(h,"is_iri"));
        String scheme = value(h, "scheme"); if (!scheme.isEmpty()) s.append(scheme).append(':');
        String host = value(h, "host"), usr = value(h, "usr"), pwd = value(h, "pwd"), port = value(h, "port");
        if(iri){host=decodeCompound(host);usr=decodeCompound(usr);pwd=decodeCompound(pwd);}
        if (!host.isEmpty() || !usr.isEmpty()) {
            s.append("//");
            if (!usr.isEmpty()) { s.append(usr); if (!pwd.isEmpty()) s.append(':').append(pwd); s.append('@'); }
            s.append(host); if (!port.isEmpty()) s.append(':').append(port);
        }
        String path = iri?decodeCompound(value(h,"path")):value(h, "path");
        if ((!host.isEmpty() || !usr.isEmpty()) && !path.isEmpty() && !path.startsWith("/")) s.append('/');
        s.append(path); String query = iri?decodeCompound(value(h,"query")):value(h, "query"), frag = iri?decodeCompound(value(h,"frag")):value(h, "frag");
        if (!query.isEmpty()) s.append('?').append(query); if (!frag.isEmpty()) s.append('#').append(frag);
        return s.toString();
    }
    public static RuntimeList newUri(RuntimeArray a, int c) { return make(arg(a,0), arg(a,1)).getList(); }
    public static RuntimeList uri(RuntimeArray a, int c) { return make("URI::Fast", arg(a,0)).getList(); }
    public static RuntimeList iri(RuntimeArray a, int c) { return make("URI::Fast::IRI", arg(a,0)).getList(); }
    public static RuntimeList new_abs(RuntimeArray a, int c) {
        if (a.size() < 3) return absolute(a, c);
        String className = arg(a, 0);
        String rel = a.get(1).value instanceof RuntimeHash ? stringify(a.get(1)) : arg(a, 1);
        String base = a.get(2).value instanceof RuntimeHash ? stringify(a.get(2)) : arg(a, 2);
        try { String resolved; if(rel.isEmpty()) resolved=base; else if(rel.startsWith("?")){String b=base.split("#",2)[0]; int q=b.indexOf('?'); if(q>=0)b=b.substring(0,q); resolved=b+rel;} else resolved=new URI(base).resolve(new URI(rel)).toString(); resolved=new URI(resolved).normalize().toString(); resolved=resolved.replaceFirst("^(\\w+://[^/]+)(?:/\\.\\.)+", "$1"); return make(className, resolved).getList(); }
        catch (Exception ex) { return make(className, rel).getList(); }
    }
    public static RuntimeList abs_uri(RuntimeArray a, int c) { return absolute(a, c); }
    public static RuntimeList absolute(RuntimeArray a, int c) {
        String rel = a.size() > 0 && a.get(0).value instanceof RuntimeHash ? stringify(a.get(0)) : arg(a,0);
        String base = a.size() > 1 && a.get(1).value instanceof RuntimeHash ? stringify(a.get(1)) : arg(a,1);
        int blessId = a.size() > 0 && a.get(0).value instanceof RuntimeBase runtimeBase ? runtimeBase.blessId
                : a.size() > 0 ? a.get(0).blessId : 0;
        String className = blessId != 0 ? NameNormalizer.getBlessStr(blessId) : "URI::Fast";
        try { String resolved; if(rel.isEmpty()) resolved=base; else if(rel.startsWith("?")){String b=base.split("#",2)[0]; int q=b.indexOf('?'); if(q>=0)b=b.substring(0,q); resolved=b+rel;} else resolved=new URI(base).resolve(new URI(rel)).toString(); resolved=new URI(resolved).normalize().toString(); resolved=resolved.replaceFirst("^(\\w+://[^/]+)(?:/\\.\\.)+", "$1"); return make(className, resolved).getList(); }
        catch (Exception ex) { return make(className, rel).getList(); }
    }
    public static RuntimeList new_html_url(RuntimeArray a,int c){ return html_url(a,c); }
    public static RuntimeList html_url(RuntimeArray a,int c){ return make("URI::Fast", arg(a,0)).getList(); }
    public static RuntimeList DESTROY(RuntimeArray a,int c){ return new RuntimeList(); }
    public static RuntimeList to_string(RuntimeArray a,int c){ return text(stringify(a.get(0))).getList(); }
    public static RuntimeList as_string(RuntimeArray a,int c){ return to_string(a,c); }
    public static RuntimeList TO_JSON(RuntimeArray a,int c){ return to_string(a,c); }
    public static RuntimeList normalize(RuntimeArray a,int c){ RuntimeScalar v=a.get(0); RuntimeHash h=object(v); put(h,"scheme",value(h,"scheme").toLowerCase()); put(h,"host",normalizeEscapes(value(h,"host").toLowerCase())); put(h,"usr",normalizeEscapes(value(h,"usr"))); put(h,"pwd",normalizeEscapes(value(h,"pwd"))); put(h,"path",normalizeEscapes(value(h,"path"))); put(h,"query",normalizeEscapes(value(h,"query").replace("+","%20"))); put(h,"frag",normalizeEscapes(value(h,"frag"))); String host=value(h,"host"); if(!host.isEmpty()&&value(h,"path").isEmpty())put(h,"path","/"); try{String p=value(h,"path"); if(!p.isEmpty())put(h,"path",new URI("x:"+p).normalize().getRawSchemeSpecificPart());}catch(Exception ignored){} return v.getList(); }
    public static RuntimeList canonical(RuntimeArray a,int c){ return normalize(a,c); }
    public static RuntimeList uri_split(RuntimeArray a,int c){ RuntimeScalar u=make("URI::Fast",arg(a,0)); RuntimeHash h=object(u); RuntimeList r=new RuntimeList(); r.elements.add(part(h,"scheme",false)); r.elements.add(part(h,"auth",false)); r.elements.add(part(h,"path",true)); r.elements.add(part(h,"query",true)); r.elements.add(part(h,"frag",false)); return r; }
    private static RuntimeScalar part(RuntimeHash h,String field,boolean compound){String v=field.equals("auth")?rawAuth(h):value(h,field); if(v.isEmpty()){if(field.equals("path"))return text(""); if(field.equals("auth")&&h.get("authority_present")!=null&&h.get("authority_present").getBoolean())return text(""); return new RuntimeScalar();} return text(compound?decodeCompound(v):decode(v));}
    public static RuntimeList encode(RuntimeArray a,int c){ RuntimeScalar out=text(encode(arg(a,0),a.size()>1?arg(a,1):"")); out.type=BYTE_STRING; return out.getList(); }
    public static RuntimeList uri_encode(RuntimeArray a,int c){ return encode(a,c); }
    public static RuntimeList url_encode(RuntimeArray a,int c){ return encode(a,c); }
    public static RuntimeList decode(RuntimeArray a,int c){ String input=arg(a,0); RuntimeScalar out=text(decode(input)); if(!validUtf8Escapes(input))out.type=BYTE_STRING; return out.getList(); }
    public static RuntimeList uri_decode(RuntimeArray a,int c){ return decode(a,c); }
    public static RuntimeList url_decode(RuntimeArray a,int c){ return decode(a,c); }

    private static RuntimeList accessor(RuntimeArray a,String field,boolean raw){ RuntimeHash h=object(a.get(0)); if(a.size()>1) h.put(field,text(raw?arg(a,1):encode(arg(a,1), allowed(field)))); String v=value(h,field); boolean iri="1".equals(value(h,"is_iri")); if(raw&&iri)v=decodeCompound(v); return text(raw?v:(field.equals("path")||field.equals("query")?decodeCompound(v):decode(v))).getList(); }
    private static String allowed(String f){ return switch(f){case "path"->"/:@!$&'()*+,;=";case "query"->"/:?@!$&'()*+,;=";case "frag"->"/:?@!$&'()*+,;=";case "usr","pwd"->"!$&'()*+,;=:[]";case "host"->"[]:";default->"";}; }
    public static RuntimeList scheme(RuntimeArray a,int c){return accessor(a,"scheme",false);} public static RuntimeList usr(RuntimeArray a,int c){return accessor(a,"usr",false);} public static RuntimeList pwd(RuntimeArray a,int c){return accessor(a,"pwd",false);} public static RuntimeList host(RuntimeArray a,int c){return accessor(a,"host",false);} public static RuntimeList port(RuntimeArray a,int c){return accessor(a,"port",false);} public static RuntimeList frag(RuntimeArray a,int c){return accessor(a,"frag",false);} public static RuntimeList fragment(RuntimeArray a,int c){return frag(a,c);}
    public static RuntimeList raw_scheme(RuntimeArray a,int c){return accessor(a,"scheme",true);} public static RuntimeList raw_path(RuntimeArray a,int c){return accessor(a,"path",true);} public static RuntimeList raw_query(RuntimeArray a,int c){return accessor(a,"query",true);} public static RuntimeList raw_frag(RuntimeArray a,int c){return accessor(a,"frag",true);} public static RuntimeList raw_usr(RuntimeArray a,int c){return accessor(a,"usr",true);} public static RuntimeList raw_pwd(RuntimeArray a,int c){return accessor(a,"pwd",true);} public static RuntimeList raw_host(RuntimeArray a,int c){return accessor(a,"host",true);} public static RuntimeList raw_port(RuntimeArray a,int c){return accessor(a,"port",true);}
    public static RuntimeList get_path(RuntimeArray a,int c){return text(decodeCompound(value(object(a.get(0)),"path"))).getList();} public static RuntimeList get_query(RuntimeArray a,int c){return text(decodeCompound(value(object(a.get(0)),"query"))).getList();}
    public static RuntimeList raw_auth(RuntimeArray a,int c){ RuntimeHash h=object(a.get(0)); if(a.size()>1){for(String k:new String[]{"usr","pwd","host","port"})put(h,k,"");parseAuthority(h,arg(a,1),true);} RuntimeScalar r=text(rawAuth(h)); r.type=BYTE_STRING; return r.getList(); }
    private static String rawAuth(RuntimeHash h){String s=value(h,"usr");if(!s.isEmpty()&&!value(h,"pwd").isEmpty())s+=":"+value(h,"pwd");if(!s.isEmpty())s+="@";s+=value(h,"host");if(!value(h,"port").isEmpty())s+=":"+value(h,"port");return s;}
    public static RuntimeList get_auth(RuntimeArray a,int c){RuntimeHash h=object(a.get(0)); String s=value(h,"usr"); if(!s.isEmpty()&&!value(h,"pwd").isEmpty()) s += ":"+value(h,"pwd"); if(!s.isEmpty()) s += "@"; s += value(h,"host"); if(!value(h,"port").isEmpty()) s += ":"+value(h,"port"); return text(decode(s)).getList();}
    public static RuntimeList clear_scheme(RuntimeArray a,int c){return clear(a,"scheme");} public static RuntimeList clear_path(RuntimeArray a,int c){return clear(a,"path");} public static RuntimeList clear_query(RuntimeArray a,int c){return clear(a,"query");} public static RuntimeList clear_frag(RuntimeArray a,int c){return clear(a,"frag");} public static RuntimeList clear_usr(RuntimeArray a,int c){return clear(a,"usr");} public static RuntimeList clear_pwd(RuntimeArray a,int c){return clear(a,"pwd");} public static RuntimeList clear_host(RuntimeArray a,int c){return clear(a,"host");} public static RuntimeList clear_port(RuntimeArray a,int c){return clear(a,"port");} public static RuntimeList clear_auth(RuntimeArray a,int c){ RuntimeHash h=object(a.get(0)); for(String k:new String[]{"usr","pwd","host","port"}) put(h,k,""); return new RuntimeList(); }
    private static RuntimeList clear(RuntimeArray a,String f){put(object(a.get(0)),f,""); return new RuntimeList();}
    public static RuntimeList set_path(RuntimeArray a,int c){RuntimeHash h=object(a.get(0)); put(h,"path",encode(arg(a,1),allowed("path"))); return new RuntimeList();} public static RuntimeList set_query(RuntimeArray a,int c){put(object(a.get(0)),"query",encode(arg(a,1),allowed("query"))); return new RuntimeList();} public static RuntimeList set_auth(RuntimeArray a,int c){RuntimeHash h=object(a.get(0));for(String k:new String[]{"usr","pwd","host","port"})put(h,k,"");parseAuthority(h,arg(a,1),false);return new RuntimeList();}
    public static RuntimeList set_path_array(RuntimeArray a,int c){RuntimeHash h=object(a.get(0)); RuntimeArray ar=a.size()>1&&a.get(1).value instanceof RuntimeArray?(RuntimeArray)a.get(1).value:null; if(ar==null){put(h,"path","");return new RuntimeList();} StringBuilder b=new StringBuilder("/"); for(int i=0;i<ar.size();i++){if(i>0)b.append('/');b.append(encode(ar.get(i).toString(),""));} put(h,"path",b.toString());return new RuntimeList();}
    private static RuntimeList splitPath(RuntimeArray a,boolean compat){String p=value(object(a.get(0)),"path"); RuntimeArray r=new RuntimeArray(); if(!p.isEmpty()){String[] parts=p.split("/",-1); int start=p.startsWith("/")?1:0; int end=parts.length-(p.endsWith("/")?1:0); if(compat&&p.startsWith("/"))r.push(text("")); for(int i=start;i<end;i++)r.push(text(decode(parts[i])));} return r.createReference().getList();}
    public static RuntimeList split_path(RuntimeArray a,int c){return splitPath(a,false);}
    public static RuntimeList split_path_compat(RuntimeArray a,int c){return splitPath(a,true);}
    private static String queryDecode(String s){return decode(s.replace("+"," "));}
    private static Map<String,List<String>> query(RuntimeScalar uri){Map<String,List<String>> out=new LinkedHashMap<>(); String q=value(object(uri),"query"); if(q.isEmpty())return out; for(String pair:q.split("[&;]",-1)){int eq=pair.indexOf('='); String k=queryDecode(eq<0?pair:pair.substring(0,eq)); if(k.isEmpty())continue; List<String> v=out.computeIfAbsent(k,x->new ArrayList<>()); if(eq>=0)v.add(queryDecode(pair.substring(eq+1)));} return out;}
    public static RuntimeList get_query_keys(RuntimeArray a,int c){RuntimeHash h=new RuntimeHash(); for(String k:query(a.get(0)).keySet())h.put(k,new RuntimeScalar()); return h.createReference().getList();}
    public static RuntimeList get_query_hash(RuntimeArray a,int c){RuntimeHash h=new RuntimeHash(); for(Map.Entry<String,List<String>> e:query(a.get(0)).entrySet()){RuntimeArray vals=new RuntimeArray();for(String v:e.getValue())vals.push(text(v));h.put(e.getKey(),vals.createReference());}return h.createReference().getList();}
    public static RuntimeList get_param(RuntimeArray a,int c){if(a.size()<2||!a.get(1).getDefinedBoolean())throw new PerlCompilerException("get_param: key required"); RuntimeArray vals=new RuntimeArray(); for(String v:query(a.get(0)).getOrDefault(arg(a,1),List.of()))vals.push(text(v)); return vals.createReference().getList();}
    private static void writeQuery(RuntimeHash h,Map<String,List<String>> q,String separator){StringBuilder b=new StringBuilder(); for(Map.Entry<String,List<String>> e:q.entrySet()){List<String> values=e.getValue(); if(values.isEmpty()&&!e.getKey().isEmpty()){if(b.length()>0)b.append(separator);b.append(encode(e.getKey(),""));} for(String v:values){if(e.getKey().isEmpty())continue;if(b.length()>0)b.append(separator);b.append(encode(e.getKey(),"")).append('=');if(v!=null)b.append(encode(v,""));}}put(h,"query",b.toString());}
    private static RuntimeScalar dereference(RuntimeScalar value){while(value!=null&&value.value instanceof RuntimeScalar inner)value=inner;return value==null?new RuntimeScalar():value;}
    public static RuntimeList set_param(RuntimeArray a,int c){if(a.size()<3||!a.get(1).getDefinedBoolean()||!a.get(2).getDefinedBoolean())throw new PerlCompilerException("set_param: key and value required"); RuntimeHash h=object(a.get(0)); Map<String,List<String>> q=query(a.get(0)); String key=arg(a,1); List<String> vals=new ArrayList<>(); RuntimeScalar input=a.size()>2?dereference(a.get(2)):new RuntimeScalar(); if(input.getDefinedBoolean()){if(input.value instanceof RuntimeArray){RuntimeArray v=(RuntimeArray)input.value;for(int i=0;i<v.size();i++)vals.add(dereference(v.get(i)).toString());}else vals.add(input.toString());} q.remove(key); if(!vals.isEmpty())q.put(key,vals); String sep=a.size()>3&&!arg(a,3).isEmpty()?arg(a,3):value(h,"separator"); if(sep.isEmpty())sep="&";put(h,"separator",sep);writeQuery(h,q,sep);return new RuntimeList();}
    public static RuntimeList query_keyset(RuntimeArray a,int c){RuntimeHash h=object(a.get(0));RuntimeHash requested=(RuntimeHash)a.get(1).value;Map<String,List<String>> q=query(a.get(0));String sep=a.size()>2&&!arg(a,2).isEmpty()?arg(a,2):value(h,"separator");if(sep.isEmpty())sep="&";for(String key:new ArrayList<>(q.keySet())){RuntimeScalar v=requested.get(key);if(requested.containsKey(key)&&v!=null&&(!v.getDefinedBoolean()||!v.getBoolean()))q.remove(key);}for(Map.Entry<String,RuntimeScalar> e:requested.elements.entrySet())if(e.getValue().getDefinedBoolean()&&e.getValue().getBoolean()&&!q.containsKey(e.getKey()))q.put(e.getKey(),new ArrayList<>());put(h,"separator",sep);writeQuery(h,q,sep);return new RuntimeList();} public static RuntimeList explain(RuntimeArray a,int c){return new RuntimeList();} public static RuntimeList debug(RuntimeArray a,int c){return new RuntimeList();}
}
