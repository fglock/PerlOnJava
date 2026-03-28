# JRuby and Jython: Blog and Presentation Patterns

Research on how JRuby and Jython present themselves, for reference when writing PerlOnJava content.

## JRuby

### Homepage Messaging (jruby.org)

**Tagline:** "The Ruby Programming Language on the JVM"

**Key selling points (3 pillars):**
1. **The Best of the JVM** - High performance, Real threading, Vast array of libraries
2. **It's Just Ruby** - Ruby 3.4 compatible
3. **Platform Independent** - Easy to install, Easy migration, No hassles

**Getting started:** Simple 3-step process:
1. Extract JRuby into a directory
2. Add bin to PATH
3. Test: `jruby -v`

### Blog Post Style (blog.jruby.org)

**Major release posts (JRuby 10):**
- Long-form, detailed technical content (~2000+ words)
- Clear section headers: "Moving Forward", "Getting Started", "Riding the Rails"
- Performance benchmarks with before/after comparisons
- Code examples showing both Ruby and Java interop
- Discusses JVM features leveraged (invokedynamic, AppCDS, CRaC, Leyden)
- Acknowledges limitations and roadmap

**Performance claims:**
- Shows actual benchmark output with timing
- Compares JRuby 9.4 vs JRuby 10
- Discusses JIT warmup explicitly
- Mentions startup time improvements with specific numbers

**Release notes style:**
- Short intro paragraph with links
- Version compatibility stated clearly (Ruby 3.4)
- Thank contributors by GitHub handle
- List of issues/PRs resolved with links

### Key Themes

1. **Compatibility first** - "Ruby 3.4 compatible", test suite numbers
2. **Performance** - JIT optimization, startup improvements, benchmarks
3. **Ecosystem** - Rails support, gem compatibility, Maven integration
4. **Enterprise features** - Threading, JDBC, application servers

---

## Jython

### Homepage Messaging (jython.org)

**Opening:** "The Jython project provides implementations of Python in Java"

**Use cases (3 specific):**
1. **Embedded scripting** - Java programmers add Jython for end-user scripting
2. **Interactive experimentation** - Debug Java systems using Jython
3. **Rapid application development** - "Python programs are typically 2-10x shorter"

**Code examples:** Shows both directions:
- Java code calling Python
- Python code using Java classes

**Who uses Jython:** Lists real-world users:
- IBM Websphere, Apache PIG, ImageJ, Robot Framework, etc.

### News/Blog Style

**Release announcements:**
- Short, factual paragraphs
- Links to Maven Central for downloads
- Links to NEWS file for details
- Lists notable features as bullet points
- Mentions Java version compatibility (tested against Java 8 and 11)

**Feature highlights:**
- "slim" JAR for Gradle/Maven
- Elimination of reflective access warnings
- Locale support improvements
- Console logging via java.util.logging

### Key Themes

1. **Integration focus** - Embedding, scripting, Java interop
2. **Enterprise adoption** - Lists big-name users
3. **Practical benefits** - "2-10x shorter programs"
4. **Simplicity** - Shows code, not just describes

---

## Lessons for PerlOnJava

### What works well:

1. **Clear tagline** - "The X Programming Language on the JVM"
2. **Three-pillar messaging** - Pick 3 key benefits, make them memorable
3. **Show code early** - Both projects show working examples on homepage
4. **Bidirectional examples** - Show Perl→Java AND Java→Perl
5. **Real benchmarks** - Actual output, not just claims
6. **Acknowledge limitations** - Both are honest about what doesn't work
7. **Name real users** - If possible, list who's using it
8. **Simple getting started** - 3 steps or less

### Blog post patterns:

1. **Release notes:** Short, link to details, thank contributors
2. **Feature posts:** Longer, technical, show benchmarks
3. **Tutorial posts:** Step-by-step with code

### Things to emphasize for PerlOnJava:

| JRuby/Jython Feature | PerlOnJava Equivalent |
|----------------------|----------------------|
| Ruby/Python compatibility | Perl 5.42+ compatibility |
| Threading | JVM threading (Perl threads not implemented) |
| JDBC database access | DBI with JDBC backend |
| Embedding via JSR-223 | Same - ScriptEngine API |
| gem install | jcpan for CPAN modules |
| Rails support | N/A (but CPAN ecosystem) |
| Fast startup (AppCDS/CRaC) | Potential future work |

### PerlOnJava differentiators:

1. **Single JAR** - Unlike JRuby which has multiple distribution options
2. **150+ modules bundled** - No separate install needed
3. **Dual backend** - JVM + Internal VM for different use cases
4. **No native toolchain** - jcpan works without make/gcc

---

## Sources

- https://www.jruby.org/
- https://blog.jruby.org/
- https://www.jruby.org/2026/03/04/jruby-10-0-4-0.html
- https://www.jython.org/
- https://www.jython.org/news
