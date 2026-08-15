use strict;
use warnings;
use Test::More tests => 2;
use Fcntl qw(O_WRONLY O_CREAT O_TRUNC);

my $encoded_file = "/tmp/perlonjava-open-encoded-$$";
my $raw_file = "/tmp/perlonjava-open-raw-$$";

{
    use open ':encoding(UTF-8)';
    sysopen(my $handle, $encoded_file, O_WRONLY | O_CREAT | O_TRUNC) or die $!;
    print {$handle} chr(0xF3);
    close $handle;
}

{
    no open;
    sysopen(my $handle, $raw_file, O_WRONLY | O_CREAT | O_TRUNC) or die $!;
    print {$handle} chr(0xF3);
    close $handle;
}

sub file_hex {
    my ($file) = @_;
    open my $handle, '<:raw', $file or die $!;
    local $/;
    return unpack('H*', <$handle>);
}

is(file_hex($encoded_file), 'c3b3', 'use open applies to its lexical scope');
is(file_hex($raw_file), 'f3', 'open defaults do not leak into another scope');
unlink $encoded_file, $raw_file;
