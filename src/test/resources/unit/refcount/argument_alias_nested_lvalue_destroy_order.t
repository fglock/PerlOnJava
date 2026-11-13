use strict;
use warnings;
use Test::More tests => 4;

our $destroy_count = 0;
my $output = '';
open my $capture, '>', \$output or die "cannot capture stdout: $!";

{
    local *STDOUT = $capture;

    package Thing;

    sub new_hash { bless {}, shift }
    sub new_array { bless [], shift }

    sub DESTROY {
        $main::destroy_count++;
        print "DESTROY\n";
    }

    package main;

    our $v;
    our $a;
    our $s;

    sub inspect {
        print "inside before undef\n";
        undef $main::v;
        print "inside after undef: ",
            defined($_[0]) ? "defined\n" : "undef\n";
    }

    sub inspect_array {
        print "inside array before undef\n";
        undef $main::a;
        print "inside array after undef: ",
            defined($_[0]) ? "defined\n" : "undef\n";
    }

    sub inspect_shift {
        print "inside shift before undef\n";
        undef $main::s;
        print "inside shift after undef: ",
            defined($_[0]) ? "defined\n" : "undef\n";
        print "before shift\n";
        shift @_;
        print "after shift\n";
    }

    $v = Thing->new_hash;
    print "before call\n";
    inspect($v->{a});
    print "after call\n";

    $a = Thing->new_array;
    print "before array call\n";
    inspect_array($a->[0]);
    print "after array call\n";

    $s = Thing->new_hash;
    print "before shift call\n";
    inspect_shift($s->{a});
    print "after shift call\n";
}

like(
    $output,
    qr/\Abefore call\ninside before undef\ninside after undef: undef\nDESTROY\nafter call\n/,
    'an argument alias retains its containing hash through the callee',
);
like(
    $output,
    qr/before array call\ninside array before undef\ninside array after undef: undef\nDESTROY\nafter array call\n/,
    'an argument alias retains its containing array through the callee',
);
like(
    $output,
    qr/before shift call\ninside shift before undef\ninside shift after undef: undef\nbefore shift\nafter shift\nDESTROY\nafter shift call\n\z/,
    'removing an alias from @_ keeps its containing hash until the frame exits',
);
is($destroy_count, 3, 'each aggregate is destroyed once after its argument frame releases it');
