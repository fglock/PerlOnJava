use strict;
use warnings;
use Test::More tests => 18;

{
    use re 'debug';
    ok('regex_implementation_debug_active' =~ /regex_implementation_debug_active/,
       q{use re 'debug' enables a matching regex});

    {
        no re 'debug';
        ok('regex_implementation_debug_muted' =~ /regex_implementation_debug_muted/,
           q{no re 'debug' disables tracing lexically});
    }

    ok('regex_implementation_debug_restored' =~ /regex_implementation_debug_restored/,
       q{debug is restored after a nested scope});

    my $eval_ok = eval q{'regex_implementation_debug_eval' =~ /regex_implementation_debug_eval/};
    ok($eval_ok, 'string eval inherits lexical debug');
}

ok('regex_implementation_debug_outside' =~ /regex_implementation_debug_outside/,
   'debug does not leak out of its lexical scope');

{
    use re Debug => 'COMPILE';
    ok('regex_implementation_debug_compile' =~ /regex_implementation_debug_compile/,
       q{Debug => 'COMPILE' preserves matching});
}

{
    use re Debug => 'EXECUTE';
    ok('regex_implementation_debug_execute' =~ /regex_implementation_debug_execute/,
       q{Debug => 'EXECUTE' preserves matching});
}

{
    use re Debug => 'PARSE';
    ok('regex_implementation_debug_parse' =~ /regex_implementation_debug_parse/,
       q{Debug => 'PARSE' preserves matching});
}

{
    use re Debug => 'COMPILE';
    ok(eval q{'regex_implementation_debug_eval_compile' =~ /regex_implementation_debug_eval_compile/},
       'string eval inherits compile-only debug');
}

{
    use re Debug => 'EXECUTE';
    ok(eval q{'regex_implementation_debug_eval_execute' =~ /regex_implementation_debug_eval_execute/},
       'string eval inherits execute-only debug');
    {
        no re Debug => 'EXECUTE';
        ok(eval q{'regex_implementation_debug_eval_muted' =~ /regex_implementation_debug_eval_muted/},
           'string eval inherits an authoritative named-debug disable');
    }
}

{
    use re Debug => 'ALL';
    {
        no re Debug => 'EXECUTE';
        ok('regex_implementation_debug_no_execute' =~ /regex_implementation_debug_no_execute/,
           q{no re Debug => 'EXECUTE' disables named debug tracing});
    }
    ok('regex_implementation_debug_all_restored' =~ /regex_implementation_debug_all_restored/,
       'named debug flags are restored after a nested scope');
}

{
    use re 'debugcolor';
    ok('regex_implementation_debug_color' =~ /regex_implementation_debug_color/,
       q{use re 'debugcolor' enables tracing without changing matching});
    {
        no re 'debugcolor';
        ok('regex_implementation_debug_color_muted' =~ /regex_implementation_debug_color_muted/,
           q{no re 'debugcolor' disables tracing lexically});
    }
}

ok('regex_implementation_debug_final_outside' =~ /regex_implementation_debug_final_outside/,
   'debugcolor does not leak out of its lexical scope');

{
    use re 'eval';
    use re Debug => 'EXECUTE';
    my $runtime = '(?{ 1 })regex_implementation_debug_runtime_source';
    ok('regex_implementation_debug_runtime_source' =~ /$runtime/,
       'execute tracing preserves runtime regex source');

    my $substitution = 'regex_implementation_debug_substitution';
    $substitution =~ s/regex_implementation_debug_substitution/replaced/;
    is($substitution, 'replaced',
       'execute tracing preserves substitution semantics');
}
