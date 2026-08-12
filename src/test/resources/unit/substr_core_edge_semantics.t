use strict;
use warnings;

print "1..10\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - $name\n");
}

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, $_[0] };

check(substr('54321', -7, -5) eq '', 'negative start clips to a zero endpoint');
check(substr('54321', -7, -2) eq '543', 'negative start clips with end-relative length');
check(!@warnings, 'valid clipped negative substrings do not warn');

my $reference = [];
substr($reference, 0, 1) = 'Foo';
check(grep(/^Attempt to use reference as lvalue in substr/, @warnings),
    'reference lvalue substr warns');

@warnings = ();
my $target = 'abc';
substr($target, 3, undef, 'x');
check(grep(/^Use of uninitialized value/, @warnings),
    'undefined four-argument length warns');

for my $offset (-99, 99) {
    my $error = '';
    eval { substr($target, $offset, 0, '') };
    $error = $@;
    check($error =~ /^substr outside of string/,
        'out-of-range four-argument substr dies');
}

my $compile_error = '';
eval 'substr($target, 0, 0, q()) = q(x)';
$compile_error = $@;
check($compile_error =~ /Can't modify substr/,
    'four-argument substr cannot be an assignment target');

{
    package SubstrCounted {
        use overload '""' => sub { ++$main::stringifications; 'abc' };
    }
}
our $stringifications = 0;
my $counted = bless [], 'SubstrCounted';
substr($counted, 0, 0, '');
check($stringifications == 1, 'four-argument substr stringifies target once');

{
    use feature 'refaliasing';
    no warnings 'experimental::refaliasing';
    my %hash;
    \$hash{key} = \(my $aliased = 'baz');
    substr delete $hash{key}, 1, 1, 'o';
    check($aliased eq 'boz', 'four-argument substr accepts a loose lvalue');
}
