use strict;
use warnings;
use Test::More tests => 18;

{
    use re 'debug';
    ok('phase36_debug_active' =~ /phase36_debug_active/,
       q{use re 'debug' enables a matching regex});

    {
        no re 'debug';
        ok('phase36_debug_muted' =~ /phase36_debug_muted/,
           q{no re 'debug' disables tracing lexically});
    }

    ok('phase36_debug_restored' =~ /phase36_debug_restored/,
       q{debug is restored after a nested scope});

    my $eval_ok = eval q{'phase36_debug_eval' =~ /phase36_debug_eval/};
    ok($eval_ok, 'string eval inherits lexical debug');
}

ok('phase36_debug_outside' =~ /phase36_debug_outside/,
   'debug does not leak out of its lexical scope');

{
    use re Debug => 'COMPILE';
    ok('phase36_debug_compile' =~ /phase36_debug_compile/,
       q{Debug => 'COMPILE' preserves matching});
}

{
    use re Debug => 'EXECUTE';
    ok('phase36_debug_execute' =~ /phase36_debug_execute/,
       q{Debug => 'EXECUTE' preserves matching});
}

{
    use re Debug => 'PARSE';
    ok('phase36_debug_parse' =~ /phase36_debug_parse/,
       q{Debug => 'PARSE' preserves matching});
}

{
    use re Debug => 'COMPILE';
    ok(eval q{'phase36_debug_eval_compile' =~ /phase36_debug_eval_compile/},
       'string eval inherits compile-only debug');
}

{
    use re Debug => 'EXECUTE';
    ok(eval q{'phase36_debug_eval_execute' =~ /phase36_debug_eval_execute/},
       'string eval inherits execute-only debug');
    {
        no re Debug => 'EXECUTE';
        ok(eval q{'phase36_debug_eval_muted' =~ /phase36_debug_eval_muted/},
           'string eval inherits an authoritative named-debug disable');
    }
}

{
    use re Debug => 'ALL';
    {
        no re Debug => 'EXECUTE';
        ok('phase36_debug_no_execute' =~ /phase36_debug_no_execute/,
           q{no re Debug => 'EXECUTE' disables named debug tracing});
    }
    ok('phase36_debug_all_restored' =~ /phase36_debug_all_restored/,
       'named debug flags are restored after a nested scope');
}

{
    use re 'debugcolor';
    ok('phase36_debug_color' =~ /phase36_debug_color/,
       q{use re 'debugcolor' enables tracing without changing matching});
    {
        no re 'debugcolor';
        ok('phase36_debug_color_muted' =~ /phase36_debug_color_muted/,
           q{no re 'debugcolor' disables tracing lexically});
    }
}

ok('phase36_debug_final_outside' =~ /phase36_debug_final_outside/,
   'debugcolor does not leak out of its lexical scope');

{
    use re 'eval';
    use re Debug => 'EXECUTE';
    my $runtime = '(?{ 1 })phase36_debug_runtime_source';
    ok('phase36_debug_runtime_source' =~ /$runtime/,
       'execute tracing preserves runtime regex source');

    my $substitution = 'phase36_debug_substitution';
    $substitution =~ s/phase36_debug_substitution/replaced/;
    is($substitution, 'replaced',
       'execute tracing preserves substitution semantics');
}
