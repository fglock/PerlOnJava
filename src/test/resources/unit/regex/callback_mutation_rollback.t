use strict;
use warnings;

print "1..9\n";

my ($scalar, @array, %hash) = (0, (), ());
my $failed = 'ac' =~ /^a(?{
    $scalar = 1;
    push @array, 2;
    $hash{x} = 3;
})b$/;
print !$failed ? "ok 1\n" : "not ok 1\n";
print $scalar == 0 ? "ok 2\n" : "not ok 2\n";
print @array == 0 ? "ok 3\n" : "not ok 3\n";
print !exists $hash{x} ? "ok 4\n" : "not ok 4\n";

my ($committed_scalar, @committed_array, %committed_hash) = (0, (), ());
my $matched = 'ab' =~ /^a(?{
    $committed_scalar = 4;
    push @committed_array, 5;
    $committed_hash{x} = 6;
})b$/;
print $matched ? "ok 5\n" : "not ok 5\n";
print $committed_scalar == 4 ? "ok 6\n" : "not ok 6\n";
print join(',', @committed_array) eq '5' ? "ok 7\n" : "not ok 7\n";
print $committed_hash{x} == 6 ? "ok 8\n" : "not ok 8\n";

my @alternative;
'ac' =~ /^(?:a(?{ push @alternative, 'abandoned' })b|a(?{
    push @alternative, 'chosen'
})c)$/;
print join(',', @alternative) eq 'abandoned,chosen' ? "ok 9\n" : "not ok 9\n";
