-- Please sort tables alphabetically.
-- And columns in what seems like a "good to know first" order,
-- maybe primary key first?

-- CR_DONE whole file  07-12

--======================================================================
--  links_t
--======================================================================

------------------------------------------------------------------------
comment on table  links_t  is $_$

There's no foreign key to link_previews_t, because maybe no preview has
been downloaded yet (or maybe never — maybe broken external link).
$_$;



--======================================================================
--  link_previews_t
--======================================================================

------------------------------------------------------------------------
comment on table  link_previews_t  is $_$

Caches html <title> and <meta description> tags and any OpenGraph tags,
and/or oEmbed json, for generating html previews of links to external
things, e.g. Twitter tweets.

Sometimes Ty downloads both 1) html and OpenGraph tags directly from
the linked page, and 2) oEmbed json.
Then, there'll be two rows in this table — one with downloaded_from_url_c
= link_url_c, and one with downloaded_from_url_c = the oEmbed request url.
The oEmbed data might not include a title, and then,
if the external link is inside a paragraph, so we want to show the
title of the extenal thing only, then it's good to have any html <title>
tag too.

[defense] [lnpv_t_pk] Both link_url_c and downloaded_from_url_c are part of
the primary key — otherwise maybe an attacker could do something weird,
like the following:

    An attacker's website atkws could hijack a widget from a normal
    website victws, by posting an external link to Talkyard
    that looks like: https://atkws/widget, and then the html at
    https://atkws/widget pretends in a html tag that its oEmbed endpoint is
    VEP = https://victws/oembed?url=https://victws/widget
    and then later when someone tries to link to https://victws/widget,
    whose oEmbed endpoint is VEP for real,
    then, if lookng up by downloaded_from_url_c = VEP only,
    there'd already be a link_previews_t row for VEP,
    with link_url_c: https//atkws/widget (atkws not victws!)
    (because VEP initially got saved via the request to https://atkws/widget)
    that is, link_url_c would point to the attacker's site.
    Not sure how this could be misused — since the actual oEmbed content
    would get downloaded not from atkws, but from victws, which should
    be safe, if victws does let one download oEmbed fro there? — But feels risky.

But by including both link_url_c and downloaded_from_url_c in the primary key,
that cannot happen — when looking up https://victws/widget + VEP,
the attacker's entry wouldn't be found (because it's link_url_c is
https://atkws/..., the wrong website).

There's an index  linkpreviews_i_downl_err_at  you can use to maybe retry
failed downlads after a while.
$_$;


------------------------------------------------------------------------
comment on column  link_previews_t.link_url_c  is $_$

An extenal link that we want to show a preview for. E.g. a link to a Wikipedia page
or Twitter tweet or YouTube video, or an external image or blog post, whatever.
$_$;


------------------------------------------------------------------------
comment on column  link_previews_t.downloaded_from_url_c  is $_$

oEmbed json got downloaded from this url. Later: can be empty '' if
not oEmbed, but instead html <title> or OpenGraph tags — then
downloaded_from_url_c would be the same as link_url_c, need not save twice.
$_$;


------------------------------------------------------------------------
comment on column  link_previews_t.content_json_c  is $_$

Why up to 27 000 long? Well, this can be lots of data — an Instagram
oEmbed was 9 215 bytes, and included an inline <svg> image, and
'background-color: #F4F4F4' repeated at 8 places, and the Instagram post text
repeated twice. Better allow at least 2x more than that.
There's an appserver max length check too [oEmb_json_len].
$_$;


------------------------------------------------------------------------
comment on column  link_previews_t.status_code_c  is $_$

Is 0 if the request failed completely [ln_pv_netw_err], didn't get any response.
E.g. TCP RST or timeout. 0 means the same in a browser typically, e.g. request.abort().

However, currently (maybe always?) failed downloads are instead cached temporarily
only, in Redis, so cannot DoS attack the disk storage.  [ln_pv_downl_errs]
$_$;


------------------------------------------------------------------------
comment on column  link_previews_t.content_json_c  is $_$

Null if the request failed, got no response json. E.g. an error status code,
or a request timeout or TCP RST?   [ln_pv_downl_errs]
$_$;


--======================================================================
--
--======================================================================


