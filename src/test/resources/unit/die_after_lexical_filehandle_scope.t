use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 2;

sub read_source_in_lexical_scope {
    my ($path) = @_;
    open my $handle, '<', $path or die "open test input: $!";
    my @lines = <$handle>;
    return scalar @lines;
}

my ($writer, $path) = tempfile('lexical-handle-XXXX', TMPDIR => 1, UNLINK => 1);
print {$writer} "first\nsecond\n" or die "write test input: $!";
close $writer or die "close test input: $!";

is(read_source_in_lexical_scope($path), 2, 'read input through lexical filehandle');

sub render_template_exception {
#line 5 "template"
    eval { die 'oops!' };
    return $@;
}

is(
    render_template_exception(),
    "oops! at template line 5.\n",
    'die omits context from a filehandle closed at lexical scope exit',
);
