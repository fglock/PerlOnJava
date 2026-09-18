use feature 'signatures';
use Test::More;

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad (\@a, \$ = 222) { }";
is($@,
   "Slurpy parameter not last at signature_slurpy_diagnostic_span.t line 1, near \"222) \"\n",
   'slurpy ordering diagnostic points at the final default token');

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad2 (\@, \@b) { }";
is($@,
   "Multiple slurpy parameters not allowed at signature_slurpy_diagnostic_span.t line 1, near \"\@b) \"\n",
   'multiple slurpy diagnostic points at the second slurpy sigil');

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad2b (\$a, \@b, \$c, \$d) { }";
is($@,
   "Slurpy parameter not last at signature_slurpy_diagnostic_span.t line 1, near \"\$c,\"\n"
   . "Slurpy parameter not last at signature_slurpy_diagnostic_span.t line 1, near \"\$d) \"\n",
   'each parameter after a slurpy parameter is diagnosed');

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad3 (\@a = 222) { }";
is($@,
   "A slurpy parameter may not have a default value at signature_slurpy_diagnostic_span.t line 1, near \"222) \"\n",
   'slurpy default diagnostic points at the default expression');

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad4 (\@a =) { }";
is($@,
   "A slurpy parameter may not have a default value at signature_slurpy_diagnostic_span.t line 1, near \"=) \"\n",
   'empty slurpy default diagnostic remains anchored at its operator');

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad5 (\$a =) { }";
is($@,
   "Optional parameter lacks default expression at signature_slurpy_diagnostic_span.t line 1, near \"=) \"\n",
   'empty scalar default diagnostic is anchored at its operator');

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad6 (\$a = 1, \$b, \$c) { }";
is($@,
   "Mandatory parameter follows optional parameter at signature_slurpy_diagnostic_span.t line 1, near \"\$b,\"\n"
   . "Mandatory parameter follows optional parameter at signature_slurpy_diagnostic_span.t line 1, near \"\$c) \"\n",
   'every mandatory parameter after an optional one is diagnosed');

eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad_comma (, \$a) { }";
is($@,
   "syntax error at signature_slurpy_diagnostic_span.t line 1, near \"(,\"\n",
   'leading comma in a signature is a syntax error');

SKIP: {
    skip 'Perl 5.40 changed this signature recovery diagnostic', 3 if $] < 5.040;
    eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad_hash (\$#foo\na) { }";
    is($@,
       "'#' not allowed immediately following a sigil in a subroutine signature at signature_slurpy_diagnostic_span.t line 1, near \"(\$\"\n"
       . "syntax error at signature_slurpy_diagnostic_span.t line 2, near \"a\"\n",
       'a hash marker immediately after a signature sigil is recovered with both diagnostics');

    for my $sigil ('@', '%') {
        eval "#line 1 signature_slurpy_diagnostic_span.t\nsub bad_hash (${sigil}#foo\na) { }";
        is($@,
           "'#' not allowed immediately following a sigil in a subroutine signature at signature_slurpy_diagnostic_span.t line 1, near \"(${sigil}\"\n"
           . "syntax error at signature_slurpy_diagnostic_span.t line 2, near \"a\"\n",
           "a hash marker immediately after ${sigil} in a signature is recovered with both diagnostics");
    }
}

SKIP: {
    skip 'named parameters require Perl 5.40', 9 if $] < 5.040;

    eval "#line 1 signature_slurpy_diagnostic_span.t\n"
        . "no warnings; sub bad7 (:\$) { }";
    is($@,
       "Named parameters must actually have a name at signature_slurpy_diagnostic_span.t line 1, near \"(:\$\"\n",
       'nameless named-parameter diagnostic includes its parameter prefix');

    eval "#line 1 signature_slurpy_diagnostic_span.t\n"
        . "no warnings; sub bad8 (:\$x, \$y) { }";
    is($@,
       "Positional parameter follows named parameter at signature_slurpy_diagnostic_span.t line 1, near \"\$y) \"\n",
       'positional parameters cannot follow named parameters');

    eval "#line 1 signature_slurpy_diagnostic_span.t\n"
        . "no warnings; sub bad9 (\@a, :\$b) { }";
    is($@,
       "Slurpy parameter not last at signature_slurpy_diagnostic_span.t line 1, near \":\$b) \"\n",
       'slurpy-order diagnostic includes a following named parameter');

    eval "#line 1 signature_slurpy_diagnostic_span.t\n"
        . "no warnings; sub bad10 (:\$x, :\$x) { }";
    is($@,
       "Duplicated subroutine parameter name at signature_slurpy_diagnostic_span.t line 1, near \":\$x) \"\n",
       'duplicate named parameters are rejected at the repeated parameter');

    eval "#line 1 signature_slurpy_diagnostic_span.t\n"
        . "no warnings; sub bad11 (\$x = 1, :\$y) { }";
    is($@,
       "Mandatory parameter follows optional parameter at signature_slurpy_diagnostic_span.t line 1, near \":\$y) \"\n",
       'mandatory named parameters cannot follow optional positional ones');

    eval 'sub named_missing (:$alpha, :$beta) { }';
    die $@ if $@;
    eval { named_missing() };
    like($@, qr/Missing required named parameters 'alpha', 'beta'/,
         'named signatures report all missing required parameters');

    eval 'sub named_extra (:$alpha) { }';
    die $@ if $@;
    eval { named_extra(alpha => 1, gamma => 2, delta => 3) };
    like($@, qr/Unrecognized named parameters 'gamma', 'delta'/,
         'named signatures report all unrecognized parameters');

    eval { named_extra(alpha => 1, 'a' .. 'z') };
    like($@, qr/Unrecognized named parameters 'a', 'c', 'e', 'g', 'i', \.\.\./,
         'unrecognized named parameters are capped in diagnostics');

    eval { named_missing(alpha => 1) };
    like($@, qr/Missing required named parameter 'beta'/,
         'a single missing named parameter retains singular wording');
}

done_testing;
