package Text::Markdown::Hoedown;
use 5.008005;
use strict;
use warnings;
use parent qw(Exporter);

our $VERSION = '1.03';
our @EXPORT = qw(markdown markdown_toc);

use XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

package Text::Markdown::Hoedown::Renderer::Callback;

our $AUTOLOAD;

sub AUTOLOAD {
    my ($self, $callback) = @_;
    (my $name = $AUTOLOAD) =~ s/.*:://;
    return if $name eq 'DESTROY';
    $self->{$name} = $callback;
    return $self;
}

package Text::Markdown::Hoedown;

sub markdown {
    my $str = shift;
    my %args = (
        html_options    => 0,
        extensions      => 0,
        max_nesting     => 16,
        toc_nesting_lvl => 99,
        @_,
    );
    my $renderer = Text::Markdown::Hoedown::Renderer::HTML->new(
        $args{html_options}, $args{toc_nesting_lvl});
    my $md = Text::Markdown::Hoedown::Markdown->new(
        $args{extensions}, $args{max_nesting}, $renderer);
    return $md->render($str);
}

sub markdown_toc {
    my $str = shift;
    my %args = (
        nesting_level => 6,
        extensions    => 0,
        max_nesting   => 16,
        @_,
    );
    my $renderer = Text::Markdown::Hoedown::Renderer::HTMLTOC->new(
        $args{nesting_level});
    my $md = Text::Markdown::Hoedown::Markdown->new(
        $args{extensions}, $args{max_nesting}, $renderer);
    return $md->render($str);
}

1;

__END__

=head1 NAME

Text::Markdown::Hoedown - Hoedown-compatible Markdown rendering

=head1 DESCRIPTION

This is the PerlOnJava port of Text::Markdown::Hoedown 1.03.  The original
Perl interface is preserved and its native Hoedown implementation is replaced
by the CommonMark Java library bundled with PerlOnJava.

=head1 AUTHOR

Original module by tokuhirom E<lt>tokuhirom@gmail.comE<gt>.

=head1 COPYRIGHT AND LICENSE

Copyright (C) tokuhirom.  This library is free software; you may redistribute
it and/or modify it under the same terms as Perl itself.

=cut
