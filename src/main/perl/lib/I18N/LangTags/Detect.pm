package I18N::LangTags::Detect;

use strict;
use warnings;
use Exporter ();
use I18N::LangTags ();

our $VERSION = '1.06';
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(ambient_langprefs detect http_accept_langs);

sub _uniq {
    my %seen;
    return grep { defined($_) && length($_) && !$seen{$_}++ } @_;
}

sub http_accept_langs {
    my $header = shift;
    $header = $ENV{HTTP_ACCEPT_LANGUAGE} unless defined $header;
    return unless defined $header && length $header;

    my @items;
    my $order = 0;
    for my $part (split /,/, $header) {
        $part =~ s/\A\s+|\s+\z//g;
        next unless length $part;
        my ($tag, @params) = split /\s*;\s*/, $part;
        my $q = 1;
        for my $param (@params) {
            $q = $1 if $param =~ /\Aq=([0-9.]+)\z/i;
        }
        my $lang = I18N::LangTags::locale2language_tag($tag);
        push @items, [$lang, $q, $order++] if defined $lang;
    }

    return _uniq(map $_->[0],
        sort { $b->[1] <=> $a->[1] || $a->[2] <=> $b->[2] } @items);
}

sub ambient_langprefs {
    if (defined $ENV{HTTP_ACCEPT_LANGUAGE}
            && (defined $ENV{REQUEST_METHOD} || defined $ENV{GATEWAY_INTERFACE})) {
        my @http = http_accept_langs($ENV{HTTP_ACCEPT_LANGUAGE});
        return @http if @http;
    }

    my @prefs;
    push @prefs, split /:/, $ENV{LANGUAGE} if defined $ENV{LANGUAGE};
    push @prefs, http_accept_langs($ENV{HTTP_ACCEPT_LANGUAGE})
        if defined $ENV{HTTP_ACCEPT_LANGUAGE};
    push @prefs, grep { defined && length }
        $ENV{LC_ALL}, $ENV{LC_MESSAGES}, $ENV{LANG};

    @prefs = map I18N::LangTags::locale2language_tag($_), @prefs;
    @prefs = _uniq(@prefs);
    push @prefs, 'en' unless @prefs;
    return @prefs;
}

sub detect {
    return I18N::LangTags::implicate_supers(ambient_langprefs());
}

1;
