use strict;
use warnings;
use Test::More;
use Test::Differences qw(eq_or_diff);
use Data::Dumper;
use JSON::PP qw(decode_json);
use B ();

# Equivalent to List::PowerSet 0.01.  Its recursive list copies must retain
# the scalar behavior Test::Differences observes with stock Perl.
sub powerset {
    return [[]] if @_ == 0;
    my $first = shift;
    my $pow = powerset(@_);
    return [ map { [$first, @$_], [@$_] } @$pow ];
}

eq_or_diff(
    powerset(qw(1 2 3)),
    [[1, 2, 3], [2, 3], [1, 3], [3], [1, 2], [2], [1], []],
    'recursive copies of numeric-looking qw values match numeric literals',
);

# HTTP::BrowserDetect compares a computed numeric zero with a JSON string
# fixture through this same Data::Dumper-backed comparison path.
sub browser_major { return 0 }
my $browser_fixture = decode_json('{"browser_major":"0"}');
eq_or_diff(browser_major(), $browser_fixture->{browser_major},
    'method-returned numeric zero and JSON string zero compare through Test::Differences');

my $document = decode_json('{"number":1,"string":"1"}');
my $number_flags = B::svref_2object(\$document->{number})->FLAGS;
my $string_flags = B::svref_2object(\$document->{string})->FLAGS;
ok($number_flags & (B::SVp_IOK() | B::SVp_NOK()),
    'JSON numeric token has numeric scalar flags');
ok(!($number_flags & B::SVp_POK()),
    'JSON numeric token does not have string-only flags');
ok($string_flags & B::SVp_POK(),
    'JSON string token has string flags');
ok(!($string_flags & (B::SVp_IOK() | B::SVp_NOK())),
    'JSON string token does not gain numeric flags');

sub abs2rel {
    return if !@_;
    my @result = $_[0];
    for my $i (1 .. $#_) {
        push @result, $_[$i] - $_[$i - 1];
    }
    return @result;
}

eq_or_diff(
    [abs2rel(qw(1 2 3))],
    [qw(1 1 1)],
    'arithmetic on string inputs preserves the comparison-visible string channel',
);

local $Data::Dumper::Terse = 1;
is(Dumper('nonnumeric'), "'nonnumeric'\n", 'ordinary nonnumeric strings remain strings');

done_testing;
