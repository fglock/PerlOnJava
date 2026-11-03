use strict;
use warnings;
use Test::More tests => 2;

our $destroy_count = 0;
my $output = '';
open my $capture, '>', \$output or die "cannot capture stdout: $!";

{
    local *STDOUT = $capture;

    package Thing;

    sub new { bless {}, shift }

    sub DESTROY {
        $main::destroy_count++;
        print "DESTROY\n";
    }

    package main;

    our $v;

    sub inspect {
        print "inside before undef\n";
        undef $main::v;
        print "inside after undef: ",
            defined($_[0]) ? "defined\n" : "undef\n";
    }

    $v = Thing->new;
    print "before call\n";
    inspect($v->{a});
    print "after call\n";
}

is(
    $output,
    "before call\n"
        . "inside before undef\n"
        . "inside after undef: undef\n"
        . "DESTROY\n"
        . "after call\n",
    'an argument alias retains its containing hash through the callee',
);
is($destroy_count, 1, 'the hash is destroyed once when the argument frame releases it');
