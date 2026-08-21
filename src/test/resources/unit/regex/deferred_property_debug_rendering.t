use strict;
use warnings;
use Test::More;

no warnings 'once';

our (%rx, %calls, @fold_modes);

{
    use re Debug => 'COMPILE';
    BEGIN {
        $rx{positive} = qr/^\p{IsA26Positive}$/;
        $rx{token_negative} = qr/^\P{IsA26TokenNegative}$/;
        $rx{outer_negative} = qr/^[^\p{IsA26OuterNegative}]$/;
        $rx{mixed_static} = qr/^[Q\p{IsA26MixedStatic}]$/;
        $rx{high_static} = qr/^[\x{100}\p{IsA26HighStatic}]$/;
        $rx{duplicate} = qr/^[\p{IsA26Duplicate}\p{IsA26Duplicate}]$/;
        $rx{folded} = qr/^\p{IsA26Folded}$/i;
        {
            package A26DeferredPackage;
            $main::rx{package} = qr/^\p{IsLocal}$/;
        }
    }
}

is(scalar(keys %rx), 8,
    'all forward callback patterns construct while debug description is active');
is(0 + keys(%calls), 0,
    'constructing and describing forward properties invokes no callback');

{
    no strict 'refs';
    *{'main::IsA26Positive'} = sub {
        ++$calls{positive};
        return "0041\n";
    };
    *{'main::IsA26TokenNegative'} = sub {
        ++$calls{token_negative};
        return "0041\n";
    };
    *{'main::IsA26OuterNegative'} = sub {
        ++$calls{outer_negative};
        return "0041\n";
    };
    *{'main::IsA26MixedStatic'} = sub {
        ++$calls{mixed_static};
        return "0041\n";
    };
    *{'main::IsA26HighStatic'} = sub {
        ++$calls{high_static};
        return "0041\n";
    };
    *{'main::IsA26Duplicate'} = sub {
        ++$calls{duplicate};
        return "0041\n";
    };
    *{'main::IsA26Folded'} = sub {
        ++$calls{folded};
        push @fold_modes, $_[0] ? 'i' : 's';
        return "0061\n";
    };
    *{'A26DeferredPackage::IsLocal'} = sub {
        ++$calls{package};
        return "0041\n";
    };
}

ok('A' =~ $rx{positive}, 'positive deferred property matches when reached');
is($calls{positive}, 1, 'positive property is invoked once');
ok('A' =~ $rx{positive}, 'positive property remains stable on repeated match');
is($calls{positive}, 1, 'positive property result remains cached');

ok('B' =~ $rx{token_negative}, 'token-negated deferred property matches outside set');
is($calls{token_negative}, 1, 'token-negated property is invoked once');
ok('A' !~ $rx{token_negative}, 'token-negated property excludes returned member');
is($calls{token_negative}, 1, 'token-negated property remains cached');

ok('B' =~ $rx{outer_negative}, 'outer-negated deferred class matches outside set');
is($calls{outer_negative}, 1, 'outer-negated property is invoked once');
ok('A' !~ $rx{outer_negative}, 'outer-negated class excludes returned member');
is($calls{outer_negative}, 1, 'outer-negated property remains cached');

ok('A' =~ $rx{mixed_static}, 'deferred member survives a mixed static class');
is($calls{mixed_static}, 1, 'mixed static property is invoked once');
ok('Q' =~ $rx{mixed_static}, 'mixed class retains its static member');
is($calls{mixed_static}, 1, 'mixed static class remains cached');

ok('A' =~ $rx{high_static}, 'deferred member survives a high static class');
is($calls{high_static}, 1, 'high static property is invoked once');
ok("\x{100}" =~ $rx{high_static}, 'high static class retains U+0100');
is($calls{high_static}, 1, 'high static class remains cached');

ok('A' =~ $rx{duplicate}, 'duplicate deferred terms match their shared result');
is($calls{duplicate}, 1, 'duplicate terms invoke their callback only once');
ok('A' =~ $rx{duplicate}, 'duplicate terms remain stable on repeated match');
is($calls{duplicate}, 1, 'duplicate-term result remains cached');

ok('a' =~ $rx{folded}, 'folded callback matches its returned lowercase member');
is($calls{folded}, 1, 'folded property is invoked once');
ok('A' !~ $rx{folded}, 'folded callback result is authoritative, not case-closed');
is_deeply(\@fold_modes, ['i'], 'fold mode is passed once and remains cached');

ok('A' =~ $rx{package}, 'bare deferred property uses construction package');
is($calls{package}, 1, 'package-local property is invoked once');
ok('A' =~ $rx{package}, 'package-local property remains stable on repeated match');
is($calls{package}, 1, 'package-local property result remains cached');

done_testing;
