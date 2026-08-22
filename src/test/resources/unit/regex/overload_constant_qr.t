#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use overload ();

our (@CALLS, @PROVENANCE, $COUNT);

{
    package A86::QR;
    use overload q{""} => sub {
        my $text = ${$_[0]};
        return qr/(?{ $main::COUNT++ })$text/;
    }, fallback => 1;
}

{
    BEGIN {
        overload::constant qr => sub {
            push @main::CALLS, [ @_ ];
            return bless \$_[1], 'A86::QR';
        };
    }
    $COUNT = 0;
    my $re = qr/^foo$/;
    ok('foo' =~ $re, 'qr constant handler may return a regex-valued object');
    is($COUNT, 1, 'callback supplied by overloaded constant executes');
    is(scalar @CALLS, 1, 'handler is called once for one literal segment');
    is($CALLS[0][0], '^foo$', 'handler receives raw regex source');
    is($CALLS[0][1], '^foo$', 'handler receives cooked regex source');
    is($CALLS[0][2], 'qq', 'handler receives the qq constant category');

    $COUNT = 0;
    my $eval_re = eval q{ qr/^bar$/ };
    ok('bar' =~ $eval_re, 'eval-compiled overloaded regex matches');
    is($COUNT, 1, 'regex-valued overload callback survives eval compilation');
}

{
    package A86::Chr;
    use overload q{""} => sub { chr(${$_[0]}) }, fallback => 1;
}

{
    BEGIN {
        overload::constant qr => sub {
            return bless \$_[1], 'A86::Chr';
        };
    }
    $COUNT = 0;
    ok("\x80\x{100}" =~ /128(?{ $COUNT++ })256/,
       'constant overload can change byte and Unicode pattern segments');
    is($COUNT, 1, 'callback offsets survive overloaded segment widths');
}

{
    package A86::Modify;
    use overload
        q{""} => sub { $_[0][0] },
        q{.} => sub {
            my ($left, $right) = @_;
            $left = $left->[0] if ref $left;
            $right = $right->[0] if ref $right;
            my $joined = "$left$right";
            $joined =~ s/wanted/replaced/;
            return bless [ $joined ], __PACKAGE__;
        },
        fallback => 1;
}

{
    BEGIN {
        overload::constant qr => sub {
            return bless [ $_[1] ], 'A86::Modify';
        };
    }
    use re 'eval';
    my $wanted = 'wrong';
    my $replaced = 'right';
    my $suffix = '!';
    ok('right!' =~ /^(??{ $wanted })$suffix$/,
       'overloaded concatenation can alter callback source before compilation');
}

{
    BEGIN {
        overload::constant qr => sub {
            push @main::PROVENANCE, [
                utf8::is_utf8($_[0]) ? 1 : 0,
                utf8::is_utf8($_[1]) ? 1 : 0,
                unpack('H*', $_[0]),
            ];
            return $_[1];
        };
    }
    my $ascii = qr/abc/;
    my $escaped_wide = qr/\x{100}/;
    {
        use utf8;
        my $source_wide = qr/é/;
    }
}

is_deeply(\@PROVENANCE,
          [[0, 0, '616263'], [0, 0, '5c787b3130307d'], [0, 1, 'c3a9']],
          'raw keeps source octets while cooked preserves source provenance');

done_testing();
