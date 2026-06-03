use Test::More tests => 2;

my $pad = ('$dummy = $dummy + 1;' . "\n") x 3500;

my $code = <<'PERL';
package TieOffset;
sub TIESCALAR {
    bless { text => $_[1], full_length => $_[2], prev => $_[3] ? -1 : 0 }, $_[0]
}
sub FETCH {
    my $self = $_[0];
    return $self->{full_length} - length(${ $self->{text} }) + $self->{prev};
}
sub STORE { die "store" }

package main;
sub fallback_scalar_ref_assignment {
    my $dummy = 0;
__PAD__
    my $direct;
    my $direct_ref = \$direct;
    $direct = "updated";

    my $text;
    tie my $offset, "TieOffset", \$text, 8;
    tie my $previous_offset, "TieOffset", \$text, 8, 1;
    $text = "a  b   c";
    my $before = $offset;
    substr($text, 0, 1) = "";
    my $after = $offset;
    my $previous = $previous_offset;

    return ($$direct_ref, "$before:$after:$previous");
}
PERL

$code =~ s/__PAD__/$pad/;
eval $code;
die $@ if $@;

my ($direct, $offsets) = fallback_scalar_ref_assignment();
is($direct, "updated", 'interpreter fallback preserves direct scalar refs across lexical assignment');
is($offsets, "0:1:0", 'interpreter fallback preserves tied counter refs across lexical assignment');
