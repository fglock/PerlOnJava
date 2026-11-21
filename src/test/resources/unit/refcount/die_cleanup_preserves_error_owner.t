use strict;
use warnings;
use Test::More tests => 1;

sub End::DESTROY { $_[0]->() }

sub end (&) {
    my ($callback) = @_;
    return bless(sub { $callback->() }, 'End');
}

my $die_value = [qw(aa bb cc)];
my $prior_error = [qw(xx yy zz)];
my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= $_[0] };
    $@ = $prior_error;
    {
        my $guard = end { die $die_value };
        undef $guard;
    }
}
$warning =~ s/ at [^\n]*\n\z//;

is($warning, "\t(in cleanup) $die_value",
    'cleanup preserves the prior error reference owner while warning');

done_testing;
