package Test::More;

use strict;
use warnings;
use Exporter 'import';
use Symbol 'qualify_to_ref';

our @EXPORT = qw(
    plan ok is isnt like unlike cmp_ok can_ok isa_ok
    pass fail diag skip todo done_testing
);

my $Test_Count = 0;
my $Plan_Count;
my $Failed_Count = 0;

# sub import {
#     my $package = shift;
#     my $caller = caller;
# 
#     for my $symbol (@EXPORT) {
#         my $full_name = qualify_to_ref($symbol, $caller);
#         *$full_name = \&{$symbol};
#     }
# }

sub plan {
    my ($directive, $arg) = @_;
    if ($directive eq 'tests') {
        $Plan_Count = $arg;
        print "1..$Plan_Count\n";
    } elsif ($directive eq 'skip_all') {
        print "1..0 # Skipped: $arg\n";
        exit 0;
    }
}

sub ok {
    my ($test, $name) = @_;
    $Test_Count++;
    my $result = $test ? "ok" : "not ok";
    $Failed_Count++ unless $test;
    print "$result $Test_Count - $name\n";
    return $test;
}

sub is {
    my ($got, $expected, $name) = @_;
    my $test = defined $got && defined $expected && $got eq $expected;
    ok($test, $name);
    unless ($test) {
        diag("         got: " . (defined $got ? "'$got'" : "undef"));
        diag("    expected: " . (defined $expected ? "'$expected'" : "undef"));
    }
    return $test;
}

sub isnt {
    my ($got, $expected, $name) = @_;
    my $test = !defined $got || !defined $expected || $got ne $expected;
    ok($test, $name);
    unless ($test) {
        diag("         got: '$got'");
        diag("    expected: anything else");
    }
    return $test;
}

sub like {
    my ($got, $regex, $name) = @_;
    my $test = defined $got && $got =~ /$regex/;
    ok($test, $name);
    unless ($test) {
        diag("                  '$got'");
        diag("    doesn't match '$regex'");
    }
    return $test;
}

sub unlike {
    my ($got, $regex, $name) = @_;
    my $test = !defined $got || $got !~ /$regex/;
    ok($test, $name);
    unless ($test) {
        diag("                  '$got'");
        diag("          matches '$regex'");
    }
    return $test;
}

sub cmp_ok {
    my ($got, $op, $expected, $name) = @_;
    my $test = eval "$got $op $expected";
    ok($test, $name);
    unless ($test) {
        diag("         got: $got");
        diag("    expected: $expected");
    }
    return $test;
}

sub can_ok {
    my ($module, @methods) = @_;
    my $test = 1;
    for my $method (@methods) {
        unless ($module->can($method)) {
            $test = 0;
            diag("    $module cannot '$method'");
        }
    }
    ok($test, "$module can do everything we're asking");
    return $test;
}

sub isa_ok {
    my ($object, $class, $name) = @_;
    $name ||= "The object";
    my $test = defined $object && $object->isa($class);
    ok($test, "$name isa $class");
    return $test;
}

sub pass { ok(1, $_[0]) }
sub fail { ok(0, $_[0]) }

sub diag {
    my ($message) = @_;
    print STDERR "# $message\n";
}

sub skip {
    my ($reason, $count) = @_;
    for (1..$count) {
        $Test_Count++;
        print "ok $Test_Count # skip $reason\n";
    }
}

sub todo {
    my ($reason, $sub) = @_;
    our $TODO = $reason;
    $sub->();
}

sub done_testing {
    my ($count) = @_;
    $count ||= $Test_Count;
    print "1..$count\n" unless $Plan_Count;
    return $Failed_Count == 0;
}

1;

