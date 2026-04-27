package open;

# Stub for open pragma - sets default I/O layers
# PerlOnJava implementation by Flavio S. Glock

use strict;
use warnings;

our $VERSION = '1.14';

# The open pragma sets default PerlIO layers for input/output.
# PerlOnJava already defaults to UTF-8 internally for I/O, but we
# still need to apply the layers to STDIN/STDOUT/STDERR when
# C<:std> is used so that PerlIO::get_layers() reflects the layers.
# Code that introspects layers (e.g. Test2::Util::clone_io) relies on
# this so that cloned handles don't drop the :encoding/utf8 layer and
# emit spurious "Wide character in print" warnings.

sub import {
    my $class = shift;

    my @layers;
    my $apply_to_std;
    for my $arg (@_) {
        if ($arg eq ':std') {
            $apply_to_std = 1;
        }
        elsif ($arg =~ /^:/) {
            push @layers, $arg;
        }
        else {
            # IN / OUT / IO selector tokens are ignored for now;
            # PerlOnJava applies the same layers to all directions.
        }
    }

    if ($apply_to_std && @layers) {
        my $spec = join('', @layers);
        binmode(\*STDIN,  $spec);
        binmode(\*STDOUT, $spec);
        binmode(\*STDERR, $spec);
    }
}

1;
