package I18N::LangTags;

use strict;
use warnings;
use Exporter ();

our $VERSION = '0.45';
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    alternate_language_tags encode_language_tag extract_language_tags
    implicate_supers implicate_supers_strictly is_dialect_of is_language_tag
    locale2language_tag panic_languages same_language_tag similarity_language_tag
    super_languages
);

my %ALIASES = (
    'iw'        => 'he',
    'in'        => 'id',
    'ji'        => 'yi',
    'no-bok'    => 'nb',
    'no-nyn'    => 'nn',
    'i-klingon' => 'tlh',
);

sub _uniq {
    my %seen;
    return grep { defined($_) && length($_) && !$seen{$_}++ } @_;
}

sub _norm {
    my $tag = shift;
    return undef unless defined $tag;
    $tag =~ s/\A\s+|\s+\z//g;
    return undef unless length $tag;
    $tag =~ s/\..*\z//;
    $tag =~ s/\@.*\z//;
    $tag =~ tr/_/-/;
    $tag = lc $tag;
    return 'en' if $tag eq 'c' || $tag eq 'posix';
    $tag =~ s/[^a-z0-9-]+/-/g;
    $tag =~ s/-+/-/g;
    $tag =~ s/\A-|-+\z//g;
    return undef unless length $tag;
    return $ALIASES{$tag} || $tag;
}

sub is_language_tag {
    my $tag = _norm(shift);
    return 0 unless defined $tag;
    return $tag =~ /\A(?:x|i)(?:-[a-z0-9]{1,8})+\z/
        || $tag =~ /\A[a-z]{1,8}(?:-[a-z0-9]{1,8})*\z/ ? 1 : 0;
}

sub same_language_tag {
    my ($a, $b) = map _norm($_), @_;
    return 0 unless defined $a && defined $b;
    return $a eq $b ? 1 : 0;
}

sub locale2language_tag {
    return _norm(shift);
}

sub encode_language_tag {
    return _norm(shift);
}

sub extract_language_tags {
    my @out;
    for my $text (@_) {
        next unless defined $text;
        while ($text =~ /([A-Za-z]{1,8}(?:[-_][A-Za-z0-9]{1,8})*)/g) {
            my $tag = _norm($1);
            push @out, $tag if defined $tag && is_language_tag($tag);
        }
    }
    return _uniq(@out);
}

sub super_languages {
    my $tag = _norm(shift);
    return unless defined $tag;
    my @parts = split /-/, $tag;
    my @out;
    while (@parts > 1) {
        pop @parts;
        push @out, join('-', @parts);
    }
    return @out;
}

sub is_dialect_of {
    my ($dialect, $base) = map _norm($_), @_;
    return 0 unless defined $dialect && defined $base;
    return $dialect eq $base || index($dialect, "$base-") == 0 ? 1 : 0;
}

sub similarity_language_tag {
    my ($a, $b) = map _norm($_), @_;
    return 0 unless defined $a && defined $b;
    return 1 if $a eq $b;
    return 0.5 if is_dialect_of($a, $b) || is_dialect_of($b, $a);

    my @a = split /-/, $a;
    my @b = split /-/, $b;
    my $same = 0;
    while (@a && @b && $a[0] eq $b[0]) {
        shift @a;
        shift @b;
        $same++;
    }
    return $same ? $same / ($same + @a + @b) : 0;
}

sub alternate_language_tags {
    my $tag = _norm(shift);
    return unless defined $tag;

    my %reverse = reverse %ALIASES;
    my @out;
    push @out, $ALIASES{$tag} if exists $ALIASES{$tag};
    push @out, $reverse{$tag} if exists $reverse{$tag};
    push @out, 'nb' if $tag eq 'no';
    push @out, 'nn' if $tag eq 'no';
    return _uniq(map _norm($_), @out);
}

sub panic_languages {
    my @tags = map _norm($_), @_;
    return grep { defined } ('en') unless grep { defined && $_ eq 'en' } @tags;
    return;
}

sub implicate_supers {
    my @tags = grep { defined } map _norm($_), @_;
    my %seen;
    my @out;
    for (my $i = 0; $i < @tags; $i++) {
        my $tag = $tags[$i];
        push @out, $tag unless $seen{$tag}++;

        my @supers = super_languages($tag);
        my $limit = @supers;
        SUPER:
        for (my $s = 0; $s < @supers; $s++) {
            for my $later (@tags[$i + 1 .. $#tags]) {
                if ($later eq $supers[$s]) {
                    $limit = $s;
                    last SUPER;
                }
            }
        }

        for my $super (@supers[0 .. $limit - 1]) {
            push @out, $super unless $seen{$super}++;
        }
    }
    return @out;
}

sub implicate_supers_strictly {
    my @tags = grep { defined } map _norm($_), @_;
    my @supers;
    push @supers, super_languages($_) for @tags;
    return _uniq(@tags, @supers);
}

1;
