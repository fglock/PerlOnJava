use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken);

{
    package ENH_Source;

    sub new { bless { name => $_[1] }, $_[0] }
    sub DESTROY { push @main::ENH_DESTROYED, $_[0]->{name} }

    package ENH_Holder;

    sub new { bless {}, shift }

    sub install_registry {
        my ($self, $source) = @_;
        my %registry = (Artist => $source);
        $self->{registry} = \%registry;
        return;
    }
}

@main::ENH_DESTROYED = ();

my $holder = ENH_Holder->new;
my $source = ENH_Source->new("Artist");
my $weak_source = $source;
weaken($weak_source);

$holder->install_registry($source);
undef $source;

ok defined $holder->{registry}{Artist},
    "escaped named hash keeps stored object";
ok defined $weak_source,
    "weak ref to stored object remains defined";
is join(",", @main::ENH_DESTROYED), "",
    "stored object is not destroyed while escaped registry is live";
