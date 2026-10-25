package Function::Parameters::Info;

use strict;
use warnings;
use Carp ();

sub keyword { return $_[0]->{keyword} }
sub nshift { return $_[0]->{nshift} }
sub slurpy { return $_[0]->{slurpy} }
sub named_required { return wantarray ? @{$_[0]->{_named_required}} : scalar @{$_[0]->{_named_required}} }
sub named_optional { return wantarray ? @{$_[0]->{_named_optional}} : scalar @{$_[0]->{_named_optional}} }
sub positional_optional { return wantarray ? @{$_[0]->{_positional_optional}} : scalar @{$_[0]->{_positional_optional}} }

sub positional_required {
    my @parameters = @{$_[0]->{_positional_required}};
    splice @parameters, 0, $_[0]->nshift;
    return wantarray ? @parameters : scalar @parameters;
}

sub invocants {
    my @parameters = @{$_[0]->{_positional_required}};
    splice @parameters, $_[0]->nshift;
    return wantarray ? @parameters : scalar @parameters;
}

sub invocant {
    my $count = $_[0]->nshift;
    return undef if $count == 0;
    return $_[0]->{_positional_required}[0] if $count == 1;
    Carp::croak "Can't return a single invocant; this function has $count";
}

sub args_min {
    my ($self) = @_;
    return @{$self->{_positional_required}} + 2 * @{$self->{_named_required}};
}

sub args_max {
    my ($self) = @_;
    return 'Inf' if defined $self->slurpy || $self->named_required || $self->named_optional;
    return @{$self->{_positional_required}} + @{$self->{_positional_optional}};
}

package Function::Parameters::Param;

use strict;
use warnings;
use overload '""' => sub { $_[0]->{name} }, fallback => 1;

sub name { return $_[0]->{name} }
sub type { return $_[0]->{type} }

1;
