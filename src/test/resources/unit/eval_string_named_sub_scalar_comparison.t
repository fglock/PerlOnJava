#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

package Issue1231::DataFloatLike;

# Data::Float defines named classification subs by eval STRING after setting
# file-scope lexical scalar constants.  The generated sub must retain those
# scalar cells when its argument is compared to either captured value.
my @earlier_values = (1, 2, 3);
my %earlier_options = (mode => 'test');
my $earlier_scalar = 41;
my $earlier_scalar_ref = \$earlier_scalar;
my ($positive, $negative);
$positive = 1e308 * 1e308;
$negative = -$positive;

sub _install_constant {
    my ($name, $value) = @_;
    no strict 'refs';
    *{__PACKAGE__ . '::' . $name} = sub () { $value };
}

_install_constant('eval_string_endpoints_enabled', 1);
_install_constant('positive', $positive);
_install_constant('negative', $negative);
sub eval_string_is_endpoint($);

my $loaded;
{
    local $/;
    my $code = <DATA>;
    $loaded = eval $code;
}

Test::More::is($loaded, 1, 'eval STRING installs named sub that captures scalar lexicals');
Test::More::is($@, '', 'eval STRING reports no error');
Test::More::ok(eval_string_is_endpoint($positive), 'generated sub compares against positive captured scalar');
Test::More::ok(eval_string_is_endpoint($negative), 'generated sub compares against negative captured scalar');
Test::More::ok(!eval_string_is_endpoint(3), 'generated sub rejects a non-captured scalar');

Test::More::done_testing();

__DATA__
sub eval_string_is_endpoint($) {
    return undef unless eval_string_endpoints_enabled;
    my ($value) = @_;
    return $value == $positive || $value == $negative;
}
1;
