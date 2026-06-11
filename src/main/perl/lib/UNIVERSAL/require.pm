package UNIVERSAL::require;

use strict;
use warnings;

our $VERSION = '0.19';
our $ERROR = '';

package UNIVERSAL;

use strict;
use warnings;

sub require {
    my ($module, $version) = @_;

    unless (defined $module && !ref $module && length $module) {
        $UNIVERSAL::require::ERROR = 'No module name supplied';
        return 0;
    }
    unless ($module =~ /\A[A-Za-z_]\w*(?:::\w*)*\z/) {
        $UNIVERSAL::require::ERROR = "Invalid module name '$module'";
        return 0;
    }

    (my $file = $module) =~ s!::!/!g;
    $file .= '.pm';

    my $ok = eval {
        CORE::require($file);
        1;
    };
    if (!$ok) {
        $UNIVERSAL::require::ERROR = $@ || "Unable to require $module";
        return 0;
    }

    if (defined $version) {
        $ok = eval {
            $module->VERSION($version);
            1;
        };
        if (!$ok) {
            $UNIVERSAL::require::ERROR = $@ || "$module version $version required";
            return 0;
        }
    }

    $UNIVERSAL::require::ERROR = '';
    return 1;
}

sub use {
    my ($module, @imports) = @_;
    return 0 unless $module->require;

    my $caller = caller;
    return 1 unless $module->can('import');

    my $ok = eval "package $caller; \$module->import(\@imports); 1";
    if (!$ok) {
        $UNIVERSAL::require::ERROR = $@ || "Unable to import from $module";
        return 0;
    }
    return 1;
}

1;
