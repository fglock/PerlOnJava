use strict;
use warnings;
use utf8;
use Test::More;

{
    use feature 'enhanced_xx';
    no warnings 'experimental::enhanced_xx';

    my @literal_unicode = (
        [0x0085, qr/[ab]/xx],
        [0x200e, qr/[a‎b]/xx],
        [0x200f, qr/[a‏b]/xx],
        [0x2028, qr/[a b]/xx],
        [0x2029, qr/[a b]/xx],
    );
    for my $case (@literal_unicode) {
        my ($cp, $regex) = @$case;
        ok(chr($cp) =~ $regex,
            sprintf('literal U+%04X remains a class member', $cp));
    }

    for my $cp (0x0085, 0x200e, 0x200f, 0x2028, 0x2029) {
        my $char = chr($cp);
        my $source = "[a${char}b]";
        my $regex = qr/$source/xx;
        ok($char =~ $regex,
            sprintf('dynamic U+%04X remains a class member', $cp));
    }

    {
        use feature 'evalbytes';
        use bytes;
        my $nel = chr(0x85);
        my $source = "[a${nel}b]";
        my $dynamic = qr/$source/xx;
        ok($nel =~ $dynamic, 'dynamic byte NEL remains a class member');

        my $literal = evalbytes "qr/[a${nel}b]/xx";
        is($@, '', 'literal byte NEL regex compiles in eval STRING');
        ok($nel =~ $literal, 'literal byte NEL remains a class member');
    }

    my $eval_enabled = eval q{
        my $regex = qr/[a # comment
                        b]/xx;
        'b' =~ $regex && '#' !~ $regex;
    };
    is($@, '', 'enabled eval STRING compiles without error');
    ok($eval_enabled, 'eval STRING inherits enabled enhanced_xx');

    my $nested_disable = eval q{
        no warnings 'regexp';
        my $before = '#' !~ qr/[a # comment
                                  b]/xx;
        my $inside;
        {
            no feature 'enhanced_xx';
            no warnings 'regexp';
            $inside = eval q{ '#' =~ qr/[a#b]/xx };
        }
        my $after = '#' !~ qr/[a # comment
                                 b]/xx;
        $before && $inside && $after;
    };
    is($@, '', 'nested disabling eval STRING compiles without error');
    ok($nested_disable,
        'nested eval disables enhanced_xx and restores enabled outer state');
}

{
    no feature 'enhanced_xx';
    no warnings 'regexp';
    my $nested_enable = eval q{
        no warnings 'regexp';
        my $before = '#' =~ qr/[a#b]/xx;
        my $inside;
        {
            use feature 'enhanced_xx';
            no warnings 'experimental::enhanced_xx';
            $inside = eval q{
                my $regex = qr/[a # comment
                                b]/xx;
                'b' =~ $regex && '#' !~ $regex;
            };
        }
        my $after = '#' =~ qr/[a#b]/xx;
        $before && $inside && $after;
    };
    is($@, '', 'nested enabling eval STRING compiles without error');
    ok($nested_enable,
        'nested eval enables enhanced_xx and restores disabled outer state');
}

done_testing;
