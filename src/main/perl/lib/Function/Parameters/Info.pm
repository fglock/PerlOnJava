package Function::Parameters::Info;

use strict;
use warnings;

sub _parameters {
    my ($self, $key) = @_;
    my $values = $self->{$key} || [];
    return wantarray ? @$values : scalar @$values;
}

sub invocants { return _parameters($_[0], 'invocants') }
sub positional_required { return _parameters($_[0], 'positional_required') }
sub positional_optional { return _parameters($_[0], 'positional_optional') }
sub named_required { return _parameters($_[0], 'named_required') }
sub named_optional { return _parameters($_[0], 'named_optional') }
sub slurpy { return $_[0]->{slurpy} }
sub args_min { return $_[0]->{args_min} }
sub args_max { return $_[0]->{args_max} }

sub invocant {
    my @invocants = $_[0]->invocants;
    die "Function::Parameters::Info::invocant called for a function with multiple invocants\n"
        if @invocants > 1;
    return $invocants[0];
}

package Function::Parameters::Info::Parameter;

use strict;
use warnings;
use overload '""' => sub { $_[0]->{name} }, fallback => 1;

sub name { return $_[0]->{name} }
sub type { return $_[0]->{type} }

1;
