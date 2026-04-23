use strict;
use warnings;
use Test::More;

my @tests = (
    [ q{use System::Command},         0 ],
    [ q{use System::Command 'quiet'}, 1 ],
    [ q{use System::Command -quiet},  1 ],
    [ q{use System::Command 'verbose'}, undef, qr/^Unknown option 'verbose'/ ],
    [ q{use System::Command -verbose},  undef, qr/^Unknown option 'verbose'/ ],
);

plan tests => 2 * @tests;

for my $t (@tests) {
    my ( $code, $expected, $at ) = @$t;

    # clean up
    no warnings 'once';
    delete $INC{'System/Command.pm'};
    $System::Command::QUIET = 0;

    # test
    local $SIG{__WARN__} = sub { };
    is( eval "$code; \$System::Command::QUIET", $expected, $code );
    like( $@, $at || qr/^$/, 'use ' . ( $at ? 'failed' : 'succeeded' ) );
}

