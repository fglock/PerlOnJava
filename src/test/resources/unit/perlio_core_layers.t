use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($seed, $path) = tempfile();
close $seed;

for my $layer (qw(:unix :stdio :perlio :crlf)) {
    my $fh;
    ok(open($fh, "<$layer", $path), "open accepts $layer");
    ok(binmode($fh, ':pop'), ":pop is accepted after $layer");
    close $fh;
}

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    ok(!open(my $bad, '<:u', $path), 'unknown PerlIO layer fails to open');
}
like($warning, qr/Unknown PerlIO layer "u"/, 'unknown layer warning names PerlIO');

unlink $path;
done_testing();
