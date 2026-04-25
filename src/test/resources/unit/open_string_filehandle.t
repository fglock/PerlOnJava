use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

# Regression test: 2-arg / 3-arg open with a constant-string first
# argument used to die with "Modification of a read-only value
# attempted" because PerlOnJava treated the literal as a plain
# (read-only) scalar instead of looking it up as a typeglob name.
#
# Real Perl autovivifies *main::FH from the string. Discovered in
# HTML-Tree 5.07 t/oldparse.t which uses
#     open( "INFILE", "$TestInput" ) or die "$!";

my ($fh_w, $path) = tempfile(UNLINK => 1);
print {$fh_w} "line one\nline two\n";
close $fh_w;

# 2-arg form: open("FH", $path)
{
    open("LITFH1", $path) or die "open LITFH1: $!";
    my $line = <LITFH1>;
    close LITFH1;
    is($line, "line one\n", "2-arg open with literal-string filehandle works");
}

# 3-arg form: open("FH", "<", $path)
{
    open("LITFH2", "<", $path) or die "open LITFH2: $!";
    my @lines = <LITFH2>;
    close LITFH2;
    is(scalar(@lines), 2, "3-arg open with literal-string filehandle reads file");
    is($lines[1], "line two\n", "  ... and produces the right second line");
}

# Package-qualified literal name
{
    open("Foo::Bar::FH", "<", $path) or die "open Foo::Bar::FH: $!";
    my $line = <Foo::Bar::FH>;
    close Foo::Bar::FH;
    is($line, "line one\n", "package-qualified literal-string filehandle works");
}

# Lvalue scalar form must still work (regression check that the new
# StringNode case doesn't shadow the my $fh path)
{
    open(my $fh, "<", $path) or die "open my \$fh: $!";
    my $line = <$fh>;
    close $fh;
    is($line, "line one\n", "lvalue scalar form still works");
}

done_testing();
