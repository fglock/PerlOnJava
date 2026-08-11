use strict;
use warnings;
use lib 'src/test/resources/unit/lib';
use Test::More;
use Local::ExternalAllU qw(all_u);

sub all_values (&@) {
    my $predicate = shift;
    return undef unless @_;
    $predicate->() or return 0 foreach @_;
    return 1;
}

{
    package Local::PredicateProvider;
    sub all_values (&@) {
        my $predicate = shift;
        return undef unless @_;
        $predicate->() or return 0 foreach @_;
        return 1;
    }

    sub all_u (&@) {
        my $predicate = shift;
        return undef unless @_;
        $predicate->() or return 0 foreach @_;
        return 1;
    }
}

BEGIN {
    *imported_all_values = \&Local::PredicateProvider::all_values;
    *Local::PredicateFacade::all_values = \&Local::PredicateProvider::all_values;
    *twice_imported_all_values = \&Local::PredicateFacade::all_values;
}

my $single = all_values { $_ eq 'ger' } 'ger';
ok($single, 'callback sees the implicit foreach topic');

my $all_positive = all_values { $_ > 0 } 1, 2, 3;
ok($all_positive, 'implicit topic is updated for each callback');

my $has_negative = all_values { $_ > 0 } 1, -1, 3;
ok(!$has_negative, 'callback sees a false implicit topic value');

my $imported = imported_all_values { $_ eq 'ger' } 'ger';
ok($imported, 'imported prototyped callback sees the provider loop topic');

my $twice_imported = twice_imported_all_values { $_ eq 'ger' } 'ger';
ok($twice_imported, 'twice-aliased prototyped callback sees the provider loop topic');

my $all_u_result = all_u { $_ eq 'ger' } 'ger';
ok($all_u_result, 'externally imported all_u callback sees its implicit topic');

my $pattern = qr/ger/;
my $all_u_regex = all_u { $_ =~ $pattern } 'ger';
ok($all_u_regex, 'all_u callback captures and applies a compiled regex');

my $tester = sub { $_[0] =~ $pattern };
my $all_u_predicate = all_u { $tester->($_) } 'ger';
ok($all_u_predicate, 'all_u callback invokes a captured predicate with the topic');

my @all_u_list_context = all_u { $_ =~ $pattern } 'ger';
is_deeply(\@all_u_list_context, [1],
    'all_u callback preserves its implicit topic in list context');

done_testing;
