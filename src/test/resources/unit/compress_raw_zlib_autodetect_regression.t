use strict;
use warnings;
use Test::More tests => 6;

use Compress::Raw::Zlib ();
use Compress::Zlib ();

my $plain = "gzip and zlib auto-detection \xE2\x99\xA5";

sub check_inflate {
    my ($label, $encoded) = @_;
    my ($inflater) = Compress::Raw::Zlib::Inflate->new(
        ConsumeInput => 0,
        WindowBits => Compress::Raw::Zlib::WANT_GZIP_OR_ZLIB(),
    );
    ok $inflater, "$label auto-detect inflater is created";

    my $output;
    my $status = $inflater->inflate(\$encoded, \$output);
    ok $status == Compress::Raw::Zlib::Z_OK()
        || $status == Compress::Raw::Zlib::Z_STREAM_END(),
        "$label auto-detect inflate succeeds";
    is $output, $plain, "$label auto-detect inflate returns original bytes";
}

check_inflate('gzip', Compress::Zlib::memGzip($plain));
check_inflate('zlib', Compress::Zlib::compress($plain));
