use Test::More tests => 1;

my $CRLF = "\015\012";
my $token = '[-\w!\#$%&\'*+.^_\`|{}~]';
my $header = "Content-Type: text/html\015\012";

my @fields;
while ($header =~ /($token+):\s+([^$CRLF]*)/mgox) {
    push @fields, $1, $2;
}

is_deeply(\@fields, ['Content-Type', 'text/html'], 'interpolated CRLF is escaped inside negative char class');
