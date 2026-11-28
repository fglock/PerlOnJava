use strict;
use warnings;
use Test::More tests => 6;

for my $layer (qw(:perlio :crlf)) {
    my $base_fd = do {
        open my $in, '<', __FILE__ or die "open __FILE__: $!";
        fileno $in;
    };

    for (1 .. 3) {
        open my $fh, "<$layer", __FILE__ or die "open $layer: $!";
        is(fileno($fh), $base_fd, "$layer iteration $_ reuses the released descriptor");
        binmode $fh, ':pop';
    }
}
