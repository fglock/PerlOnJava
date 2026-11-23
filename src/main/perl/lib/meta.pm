package meta;

use strict;
use warnings;
use warnings::register;
BEGIN { warnings::register_categories('meta::experimental') }

# Small pure-Perl subset used by bundled CPAN modules that add methods while
# they are being loaded. XSLoader can evaluate this shim when the upstream
# meta distribution's native extension is unavailable.
sub import { }

package meta::package;

use strict;
use warnings;

sub get {
    my ($class, $name) = @_;
    return bless { name => $name }, $class;
}

sub name { $_[0]->{name} }

sub add_named_sub {
    my ($self, $name, $code) = @_;
    no strict 'refs';
    *{$self->{name} . '::' . $name} = $code;
    return $code;
}

sub add_method { shift->add_named_sub(@_) }

1;
