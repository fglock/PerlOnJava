use strict;
use warnings;

print "1..3\n";

sub named { die "named sub must not be called" }
for my $source ('do named()', 'do named(1)') {
    my $result = eval $source;
    my $number = $source eq 'do named()' ? 1 : 2;
    print !defined($result) && $@ =~ /^syntax error/
        ? "ok $number - $source is a syntax error\n"
        : "not ok $number - $source is a syntax error ($@)\n";
}

my %value = (key => 7);
my $alias = \$value{key};
my $copied = sub {
    ${$alias}++;
    return $_[0] != ${$alias};
}->(do { 1; delete $value{key} });
print $copied
    ? "ok 3 - do block copies a deleted hash element\n"
    : "not ok 3 - do block copies a deleted hash element\n";
