package Readonly::Scalar;

sub TIESCALAR {
    my $whence = (caller 2)[3];
    die "Invalid tie" unless $whence && $whence =~ /^Readonly::Readonly$/;
    my ($class, $value) = @_;
    return bless { value => $value }, $class;
}

sub FETCH { return $_[0]->{value} }

package Readonly;

use Exporter 'import';
our @EXPORT = qw(Readonly);

sub Readonly (\[$@%]@) {
    my $tieobj = eval { tie ${$_[0]}, 'Readonly::Scalar', $_[1] };
    die $@ if $@;
    return $tieobj;
}

1;
