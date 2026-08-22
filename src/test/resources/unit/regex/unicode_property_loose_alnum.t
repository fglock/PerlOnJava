use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($operator, $property) = @_;
    my $source = '\\' . $operator . '{' . $property . '}';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $regex = eval "qr/$source/";
    return ($regex, $@, \@warnings);
}

sub check_property {
    my ($operator, $property, $members, $nonmembers, $label) = @_;
    my ($regex, $error, $warnings) = compile_property($operator, $property);
    is($error, '', "$label compiles");
    is_deeply($warnings, [], "$label is quiet");
    ok($_ =~ $regex, "$label matches member $_") for @$members;
    ok($_ !~ $regex, "$label rejects nonmember $_") for @$nonmembers;
}

my $spaced = "_\tAlnum";
check_property('p', $spaced, ['A', '5'], ['-'],
    'loosely spelled Alnum');
check_property('p', "^$spaced", ['-'], ['A', '5'],
    'caret-negated loosely spelled Alnum');
check_property('P', $spaced, ['-'], ['A', '5'],
    'uppercase-P loosely spelled Alnum');
check_property('P', "^$spaced", ['A', '5'], ['-'],
    'double-negated loosely spelled Alnum');

check_property('p', "A l-n_u\tm", ['A', '5'], ['-'],
    'internal loose Alnum separators');

done_testing;
