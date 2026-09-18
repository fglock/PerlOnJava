use strict;
use warnings;
use Test::More tests => 3;

my @handles;
my $input = "line\n";
open($handles[0], '<', \$input) or die "open: $!";

my $warning;
{
    local $SIG{__WARN__} = sub { $warning = shift };
    sub read_then_warn {
        my ($handle) = @_;
        scalar <$handle>;
        warn "context";
    }
    read_then_warn($handles[0]);
}
like($warning, qr/<\$handles\[\.\.\.\]> line 1\./,
     'warn retains the original aggregate filehandle context');

my $capture_open;
eval { open $99, '<', \$input };
$capture_open = $@;
like($capture_open, qr/Modification of a read-only value attempted/,
     'open rejects a numbered capture as its filehandle lvalue');

ok(close($handles[0]), 'aggregate filehandle remains closable');
