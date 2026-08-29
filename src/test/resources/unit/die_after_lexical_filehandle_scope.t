use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 4;

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

sub read_and_return_lexical_handle {
    my ($path) = @_;
    open my $handle, '<', $path or die "open aliased test input: $!";
    my $first = <$handle>;
    return $handle;
}

my $alias = read_and_return_lexical_handle($path);

ok(defined fileno($alias), 'returned lexical filehandle keeps a live descriptor');
is(<$alias>, "second\n", 'returned lexical filehandle remains readable');
