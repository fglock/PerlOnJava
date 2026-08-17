use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Temp qw(tempdir);
use Parse::CPAN::Meta ();

my $original_dir = getcwd();
my $temporary_dir = tempdir(CLEANUP => 1);

END {
    chdir $original_dir if defined $original_dir;
}

chdir $temporary_dir or die "chdir $temporary_dir: $!";

require ExtUtils::MakeMaker;
ExtUtils::MakeMaker::WriteMakefile(
    NAME     => 'Local::ControlCharacterMetadata',
    VERSION  => '0.001',
    ABSTRACT => "metadata abstract\r",
);

open my $metadata_fh, '<', 'MYMETA.yml'
    or die "open generated MYMETA.yml: $!";
my $metadata_text = do { local $/; <$metadata_fh> };
close $metadata_fh or die "close generated MYMETA.yml: $!";

unlike(
    $metadata_text,
    qr/[\x00-\x09\x0b-\x1f\x7f]/,
    'generated MYMETA.yml removes control characters from ABSTRACT',
);

my $metadata = Parse::CPAN::Meta::LoadFile('MYMETA.yml');
is(ref($metadata), 'HASH', 'generated MYMETA.yml remains parseable metadata');
is(
    $metadata->{abstract},
    'metadata abstract',
    'generated metadata retains the printable abstract text',
);

done_testing();
