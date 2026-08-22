use strict;
use warnings;
use Test::More tests => 2;

# A lexical declared on a short-circuited RHS still exists as undef in the
# surrounding branch.  HTTP::Message::decoded_content uses this shape for
# `$is_xml`: text content skips the RHS declaration, then the UTF-8 decoder
# tests the variable.  The bytecode interpreter used to leave its register as
# Java null and crash while testing its truth value.
{
    package ShortCircuitMessage;

    sub new { bless {}, shift }
    sub content_is_text { 1 }
    sub content_is_xml { 0 }

    sub decode {
        my ($self, %opt) = @_;
        my $source = pack('C*', 76, 195, 182, 115);
        my $content_ref = \$source;

        eval {
            if ($self->content_is_text || (my $is_xml = $self->content_is_xml)) {
                my $charset = 'utf-8';
                if ($charset eq 'none') {
                }
                elsif ($charset eq 'us-ascii' || $charset eq 'iso-8859-1') {
                }
                else {
                    require Encode;
                    eval {
                        $content_ref = \Encode::decode(
                            $charset,
                            $$content_ref,
                            ($opt{charset_strict} ? Encode::FB_CROAK() : 0)
                                | Encode::LEAVE_SRC()
                        );
                    };
                    die if $@;
                    die unless defined $$content_ref;
                    if ($is_xml) {
                        $$content_ref =~ s/^x//;
                    }
                }
            }
        };
        die $@ if $@;
        return $$content_ref;
    }
}

my $decoded = ShortCircuitMessage->new->decode;
is(
    join(',', map { ord($_) } split //, $decoded),
    '76,246,115',
    'UTF-8 content is decoded after the short-circuited lexical declaration'
);
ok(utf8::is_utf8($decoded), 'decoded content carries the Unicode flag');
