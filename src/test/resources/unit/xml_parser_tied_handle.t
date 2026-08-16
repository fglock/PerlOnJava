use strict;
use warnings;
use Test::More tests => 4;
use IO::Handle;
use IO::Zlib;
use File::Temp qw(tempfile);
use Symbol qw(gensym);
use XML::Parser;

{
    package Local::ChunkHandle;

    sub TIEHANDLE {
        my ($class, $data) = @_;
        return bless { data => $data, offset => 0 }, $class;
    }

    sub READ {
        my ($self, undef, $length) = @_;
        if ($self->{offset} >= length $self->{data}) {
            $_[1] = '';
            return 0;
        }
        my $chunk = substr($self->{data}, $self->{offset}, $length);
        $_[1] = $chunk;
        $self->{offset} += length $chunk;
        return length $chunk;
    }
}

my $xml = '<root><value>from tied READ</value></root>';
my $input = gensym();
tie *$input, 'Local::ChunkHandle', $xml;
bless $input, 'Local::ChunkIO';

{
    package Local::ChunkIO;
    our @ISA = ('IO::Handle');
}

my @values;
my $parser = XML::Parser->new(
    Handlers => {
        Char => sub {
            my ($expat, $text) = @_;
            push @values, $text if $text =~ /\S/;
        },
    },
);

ok($parser->parse($input), 'XML::Parser accepts a tied input handle');
is(join('', @values), 'from tied READ', 'parser consumes bytes through tied READ');

my ($plain, $gzip_file) = tempfile(SUFFIX => '.xml.gz');
close $plain;
my $gzip_out = IO::Zlib->new($gzip_file => 'wb');
print {$gzip_out} $xml;
close $gzip_out;

@values = ();
my $gzip_in = IO::Zlib->new($gzip_file => 'rb');
ok($parser->parse($gzip_in), 'XML::Parser accepts a blessed glob input handle');
is(join('', @values), 'from tied READ', 'parser consumes decompressed IO::Zlib bytes');
close $gzip_in;
