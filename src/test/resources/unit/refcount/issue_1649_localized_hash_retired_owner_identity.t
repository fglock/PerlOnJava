use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

package Issue1649::LocalizedHashRetiredOwner;

sub DESTROY {
    push @main::DESTROYED, $_[0]{id};
}

package main;

our (%values, @DESTROYED);
@DESTROYED = ();

my ($copy, $weak);
{
    local $values{temporary};
    my $temporary = bless { id => 'temporary' },
        'Issue1649::LocalizedHashRetiredOwner';
    $copy = $temporary;
    $weak = $temporary;
    weaken($weak);
    $values{temporary} = $temporary;
}

is_deeply(\@DESTROYED, [],
    'a copied value keeps the localized hash value alive after slot restoration');
ok(defined($weak), 'the copied value keeps its weak observer defined');

undef $copy;
is_deeply(\@DESTROYED, ['temporary'],
    'dropping the last copied reference destroys the localized hash value');
ok(!defined($weak), 'the weak observer clears after the final copied reference');
ok(!exists($values{temporary}), 'the original missing hash key stays absent');

done_testing();
