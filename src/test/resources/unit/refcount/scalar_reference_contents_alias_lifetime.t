use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use Test::More tests => 12;

{
    package Local::ScalarReferenceAliasLifetime;
    our $destroyed = 0;

    sub new { bless {}, shift }
    sub DESTROY { ++$destroyed }
}

sub refcount { svref_2object($_[0])->REFCNT }

sub make_scalar_reference_aliases {
    my $value = Local::ScalarReferenceAliasLifetime->new;
    my $first = \$value;
    my $second = $first;
    return ($first, $second);
}

my ($first, $second) = make_scalar_reference_aliases();
my $weak_contents = $$second;
weaken($weak_contents);
is($Local::ScalarReferenceAliasLifetime::destroyed, 0,
    'aliases keep a returned scalar cell and its contents alive');
ok(defined $weak_contents,
    'the scalar contents remain alive after the declaring scope exits');
is(refcount($$second), 1,
    'two aliases to one scalar cell contribute one contents owner');

undef $first;
is($Local::ScalarReferenceAliasLifetime::destroyed, 0,
    'dropping one alias does not release the shared scalar contents');
ok(defined $weak_contents,
    'the remaining alias still keeps the scalar cell contents alive');
is(refcount($$second), 1,
    'dropping one alias does not change the scalar cell contents count');

my $replacement = Local::ScalarReferenceAliasLifetime->new;
my $weak_replacement = $replacement;
weaken($weak_replacement);
$$second = $replacement;
undef $replacement;
is($Local::ScalarReferenceAliasLifetime::destroyed, 1,
    'replacing the shared scalar cell releases its previous contents once');
ok(!defined $weak_contents,
    'the replaced contents weak reference clears');
ok(defined $weak_replacement,
    'the replacement remains alive through the remaining scalar reference');
is(refcount($$second), 1,
    'the shared scalar cell contributes one owner for replacement contents');

undef $second;
is($Local::ScalarReferenceAliasLifetime::destroyed, 2,
    'dropping the final alias releases the current contents once');
ok(!defined $weak_replacement,
    'the replacement weak reference clears after the final alias is released');

done_testing;
