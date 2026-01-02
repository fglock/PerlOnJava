# Open-source regex engines to look at

---

### 1. **PCRE2 (C)**

* **Closest match to Perl semantics.**
  Supports subroutine calls, recursion, verbs, backtracking control, `\K`, `\G`, named captures, etc.
* **Mature and very fast.**
  Highly optimized, tested in production everywhere.
* **Drawback:** written in C. You’d either need a JNI bridge (performance overhead + complexity) or port its VM design to Java/bytecode.
* **Good fit if:** you want an authoritative reference for how the opcodes and backtracking model can look. Even just studying PCRE2’s IR and backtracking stack gives you a roadmap.

---

### 2. **Oniguruma / Onigmo (C, Ruby’s regex)**

* **Used by Ruby.**
  Similar richness: subroutines, conditionals, callouts, verb-like constructs.
* **Unicode-aware.**
  Strong support for multibyte encodings.
* **Same issue:** C codebase → would require port or JNI.
* **Good fit if:** you want a more modular codebase than PCRE2 and are interested in multi-encoding support.

---

### 3. **RE2 (C++, Google)**

* **Non-backtracking engine.**
  Designed for safety and guaranteed linear time.
* **Not a fit** if your goal is Perl fidelity, since RE2 *intentionally omits* Perl features like backreferences, recursion, or verbs.
* **Good fit if:** you wanted a “safe mode” for sandboxing untrusted regex, but not for Perl emulation.

---

### 4. **Joni (Java, JRuby project)**

* **Oniguruma port to Java.**
  Used by JRuby to give Ruby-like regex semantics on the JVM.
* **Already integrates with JVM.**
  No JNI bridge needed; you can inspect and possibly adapt its VM design.
* **Not Perl-complete.** But it’s the closest JVM-side codebase with backtracking and features beyond `java.util.regex`.
* **Good fit if:** you want something you can drop into PerlOnJava as a starting point and then extend toward Perl’s semantics.

---

### 5. **PCRE-J (various Java ports)**

* Some partial PCRE ports exist in Java, though many are outdated and incomplete.
* **Good fit if:** you find a maintained one, but otherwise riskier than Joni.

---

### Strategy for PerlOnJava

* **Short term:** leverage Joni. It’s Java, battle-tested (JRuby depends on it), and already has a backtracking model + subroutines. You can embed it, then progressively extend its instruction set to cover Perl-only constructs (`(?{ })`, `(??{ })`, cut verbs).
* **Mid term:** study PCRE2 as the “gold standard” and port the missing instructions (the C VM maps very directly to Java bytecode).
* **Long term:** you’ll probably end up with your own forked/extended VM specialized for PerlOnJava, but you save months of design by standing on Joni/PCRE2 first.


