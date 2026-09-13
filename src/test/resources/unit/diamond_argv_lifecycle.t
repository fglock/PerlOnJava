use strict;
use warnings;
use File::Spec;
use File::Temp qw(tempfile);
use Test::More;

sub fixture {
    my ($content) = @_;
    my ($fh, $path) = tempfile();
    print {$fh} $content;
    close $fh;
    return $path;
}

my $first = fixture("first\n");
my $second = fixture("second\n");

@ARGV = ($first, $second);
my $global_text = '';
while (<>) {
    $global_text .= $_;
}
is $global_text, "first\nsecond\n",
    'diamond reads files assigned to global @ARGV';

{
    local @ARGV = ($first, $second);
    my ($text, @lines);
    while (<>) {
        $text .= "$ARGV:$_";
        push @lines, $.;
    }
    is $text, "$first:first\n$second:second\n",
        'diamond publishes the current filename through $ARGV';
    is_deeply \@lines, [1, 2], 'diamond preserves $. across @ARGV files';
}

{
    local @ARGV = ($second);
    is scalar(<>), "second\n", 'diamond restarts after a previous @ARGV traversal';
}

@ARGV = ($first);
local $/ = undef;
scalar <>;
open STDIN, '<', File::Spec->devnull or die "cannot reopen STDIN: $!";
@ARGV = ();
ok eof(), 'argumentless eof uses reopened STDIN after an active diamond reader';

done_testing;
