use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

my ($seed, $path) = tempfile();
print {$seed} 'abc';
close $seed;

open my $reader, '<:utf8', $path or die "open reader: $!";
my $buffer;
eval { sysread $reader, $buffer, 1; 1 };
is($@, "sysread() isn't allowed on :utf8 handles at $0 line 12.\n",
   'sysread uses Perl-compatible utf8-layer diagnostic');
close $reader;

open my $writer, '>:utf8', $path or die "open writer: $!";
eval { syswrite $writer, 'x'; 1 };
is($@, "syswrite() isn't allowed on :utf8 handles at $0 line 18.\n",
   'syswrite uses Perl-compatible utf8-layer diagnostic');
close $writer;

done_testing;
