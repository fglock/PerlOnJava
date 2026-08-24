use strict;
use warnings;
use Test::More tests => 8;

BEGIN {
    require './src/main/perl/lib/Text/CSV_PP.pm';
}

# Text::CSV_PP deliberately upgrades a Latin-1 character by briefly appending
# a wide character.  Removing that character must not turn the scalar back
# into an octet string.
my $nbsp = "\x{00a0}";
($nbsp = "$nbsp\x{0123}") =~ s/.$//;

is($nbsp, "\x{00a0}", 'destructive substitution retains the Latin-1 character');
ok(utf8::is_utf8($nbsp), 'destructive substitution retains the UTF-8 flag');

my @fields = ('', ' ', $nbsp, '');
my $csv = Text::CSV_PP->new({ always_quote => 1, keep_meta_info => 1 });
ok($csv, 'constructed the pure-Perl CSV implementation');
ok($csv->combine(@fields), 'combined the upgraded Latin-1 field');

my $record = $csv->string;
ok(utf8::is_utf8($record), 'Text::CSV_PP combine preserves joined UTF-8');
is($record, qq{""," ","\x{00a0}",""}, 'combined record content is unchanged');
ok($csv->parse($record), 'binary-off parser accepts the UTF-8 record');
is_deeply([$csv->fields], \@fields, 'parsed fields round-trip');
