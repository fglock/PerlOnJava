use strict;
use warnings;
use Test::More tests => 12;
use File::Spec;
use File::Temp qw(tempdir);

# Perl skips whitespace before the open() mode sigil and between the sigil
# and the I/O layer list, so '< ' means the same as '<' and '< :utf8' means
# the same as '<:utf8'.  Perl6::Slurp opens its input with '< ', which used
# to abort with "Unknown open() mode '< '" (GitHub issue #1160).

my $dir  = tempdir(CLEANUP => 1);
my $path = File::Spec->catfile($dir, 'mode.txt');

{
    open(my $out, '> ', $path) or die "open '> ': $!";
    print {$out} "first\n";
    close $out;
    ok(-s $path, "'> ' opens for writing");
}

{
    open(my $in, '< ', $path) or die "open '< ': $!";
    is(scalar(<$in>), "first\n", "'< ' opens for reading");
    close $in;
}

{
    open(my $in, "<\t", $path) or die "open '<\\t': $!";
    is(scalar(<$in>), "first\n", "tab after the mode sigil is accepted");
    close $in;
}

{
    open(my $out, '>> ', $path) or die "open '>> ': $!";
    print {$out} "second\n";
    close $out;
    open(my $in, ' <', $path) or die "open ' <': $!";
    my @lines = <$in>;
    close $in;
    is_deeply(\@lines, [ "first\n", "second\n" ],
        "'>> ' appends and ' <' reads");
}

{
    open(my $rw, '+< ', $path) or die "open '+< ': $!";
    is(scalar(<$rw>), "first\n", "'+< ' opens for read/write");
    close $rw;
}

{
    open(my $out, ' > ', $path) or die "open ' > ': $!";
    print {$out} "third\n";
    close $out;
    open(my $in, '<', $path) or die "open: $!";
    is(scalar(<$in>), "third\n", "whitespace on both sides is accepted");
    close $in;
}

# Layers still work when whitespace separates them from the mode sigil.
{
    open(my $out, '> :encoding(UTF-8)', $path) or die "open '> :encoding': $!";
    print {$out} "\x{263A}\n";
    close $out;

    open(my $in, '< :encoding(UTF-8)', $path) or die "open '< :encoding': $!";
    my $line = <$in>;
    close $in;
    is($line, "\x{263A}\n", 'whitespace before the layer list is accepted');
}

{
    open(my $in, '<:encoding(UTF-8) ', $path) or die "open '<:encoding ': $!";
    my $line = <$in>;
    close $in;
    is($line, "\x{263A}\n", 'trailing whitespace after the layer list is accepted');
}

# Duplication modes accept the same whitespace.
{
    open(my $dup, '>& ', \*STDERR) or die "dup '>& ': $!";
    ok(defined $dup, "'>& ' duplicates a handle");
    close $dup;
}

# The two-argument form keeps the filename intact.
{
    open(my $out, '>', $path) or die "open: $!";
    print {$out} "two-arg\n";
    close $out;

    open(my $in, "< $path") or die "2-arg open: $!";
    my $line = <$in>;
    close $in;
    is($line, "two-arg\n", 'two-argument open still splits mode from filename');
}

# Whitespace inside the sigil itself is not a valid mode in Perl either.
{
    my $ok = eval { open(my $fh, '+ <', $path); 1 };
    ok(!$ok, "'+ <' is still rejected");
    like($@, qr/Unknown open\(\) mode/, 'and reports an unknown mode');
}
