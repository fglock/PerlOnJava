use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);

my $entry_count = 39_000;
my $entries = join ',', map { "'key$_' => [[$_, $_, 2]]" } 1 .. $entry_count;
my $dir = tempdir(CLEANUP => 1);
my $path = "$dir/LargeGeneratedHash.pm";
open my $out, '>', $path or die "open $path: $!";
print {$out} "package LargeGeneratedHash;\nour \$factory = sub { return { $entries }; };\n1;\n"
    or die "write $path: $!";
close $out or die "close $path: $!";

my $loaded = eval { require $path; 1 };
ok($loaded, 'load a module with a large generated hash literal');
diag($@) unless $loaded;

if ($loaded && ref($LargeGeneratedHash::factory) eq 'CODE') {
    my $table = $LargeGeneratedHash::factory->();
    is(ref($table), 'HASH', 'large hash literal returns a hash');
    is(scalar keys %{$table}, $entry_count, 'all generated entries are retained');
    is_deeply($table->{key1}, [[1, 1, 2]], 'first generated entry is correct');
    is_deeply($table->{"key$entry_count"}, [[$entry_count, $entry_count, 2]],
        'last generated entry is correct');
}

done_testing;
